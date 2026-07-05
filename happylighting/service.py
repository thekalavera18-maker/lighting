from __future__ import annotations

import asyncio
from concurrent.futures import CancelledError, Future

from PySide6.QtCore import QObject, QTimer, Signal

from .bridge import AsyncBridge
from .controller import HappyLightingClient, is_likely_happylighting
from .effects import CUSTOM_EFFECTS, NATIVE_EFFECTS, custom_effect_color
from .models import AppState, LightDevice
from .settings import SettingsStore

_BLE_NOISE = (
    "winerror",
    "object has been closed",
    "no light is connected",
    "disconnected",
    "not connected",
    "bleakerror",
    "object closed",
    "connection was terminated",
    "unreachable",
)


def _is_ble_noise(msg: str) -> bool:
    low = msg.lower()
    return any(kw in low for kw in _BLE_NOISE)


class HappyLightingService(QObject):
    state_changed = Signal(object)
    status_message = Signal(str)
    error_message = Signal(str)
    _ble_dropped = Signal()  # fired from asyncio thread, handled on Qt thread

    def __init__(self) -> None:
        super().__init__()
        self.bridge = AsyncBridge()
        self.client = HappyLightingClient()
        self.settings = SettingsStore()
        self.state = AppState(
            saved_device_address=self.settings.get("last_device_address"),
            saved_device_name=self.settings.get("last_device_name"),
            brightness=int(self.settings.get("brightness", 100)),
            base_color=tuple(self.settings.get("base_color", [255, 140, 66])),
            applied_color=tuple(self.settings.get("applied_color", [255, 140, 66])),
        )
        self.client.set_brightness(self.state.brightness)
        self.client.set_base_color(self.state.base_color)
        self._custom_effect_future: Future | None = None
        self._scan_future: Future | None = None
        self._connect_future: Future | None = None
        self._startup_reconnect_future: Future | None = None
        self._shutting_down = False
        self._shutdown_complete = False
        self._suppress_ble_errors = False

        self._brightness_timer = QTimer(self)
        self._brightness_timer.setSingleShot(True)
        self._brightness_timer.setInterval(200)
        self._brightness_timer.timeout.connect(self._apply_pending_brightness)

        self._color_timer = QTimer(self)
        self._color_timer.setSingleShot(True)
        self._color_timer.setInterval(150)
        self._color_timer.timeout.connect(self._apply_pending_color)

        self._ble_dropped.connect(self._on_device_dropped)

    # ---------------------------------------------------------------- public

    def emit_state(self) -> None:
        self.state_changed.emit(self.state.as_dict())

    def set_status(self, text: str) -> None:
        self.state.status_text = text
        self.status_message.emit(text)
        self.emit_state()

    def startup(self) -> None:
        self.emit_state()
        if self.state.saved_device_address:
            self._start_saved_device_reconnect()
        else:
            self.scan_devices()

    def shutdown(self) -> None:
        if self._shutdown_complete:
            return
        self._shutting_down = True
        self._shutdown_complete = True
        self._suppress_ble_errors = True
        self._brightness_timer.stop()
        self._color_timer.stop()
        self.stop_effect(restore_static=False)
        try:
            self.bridge.submit(self.client.disconnect()).result(timeout=3.0)
        except Exception:
            pass
        self.bridge.stop()

    def scan_devices(self) -> None:
        if self._scan_future and not self._scan_future.done():
            return
        self._startup_reconnect_future = None
        self.state.scanning = True
        self.set_status("Scanning for nearby BLE devices...")
        future = self.bridge.submit(self.client.scan_devices())
        self._scan_future = future
        future.add_done_callback(self._handle_scan_complete)

    def connect_device(self, address: str | None = None) -> None:
        if self._connect_future and not self._connect_future.done():
            return
        self._suppress_ble_errors = False
        self._startup_reconnect_future = None
        target_address = address or self.state.saved_device_address
        if not target_address:
            self.error_message.emit("No device selected.")
            return
        device = next((d for d in self.state.devices if d.address == target_address), None)
        if not device:
            device = LightDevice(self.state.saved_device_name or target_address, target_address)
        self.set_status(f"Connecting to {device.name}...")
        future = self.bridge.submit(
            self.client.connect(device, self._ble_disconnected_callback)
        )
        self._connect_future = future
        future.add_done_callback(lambda done: self._handle_connect_complete(done, device))

    def disconnect_device(self) -> None:
        self._suppress_ble_errors = True
        self._brightness_timer.stop()
        self._color_timer.stop()
        self.stop_effect(restore_static=False)
        future = self.bridge.submit(self.client.disconnect())
        future.add_done_callback(self._handle_disconnect_complete)

    def reset_and_reconnect(self) -> None:
        address = self.state.saved_device_address or self.state.device_address
        if not address:
            self.error_message.emit("No saved device — connect to a device first.")
            return
        self._suppress_ble_errors = True
        self._brightness_timer.stop()
        self._color_timer.stop()
        self.stop_effect(restore_static=False)
        self.set_status("Clearing stuck BLE connection…")
        future = self.bridge.submit(self._do_reset_and_reconnect(address))
        future.add_done_callback(self._handle_reset_complete)

    def turn_on(self) -> None:
        self._submit_action(self.client.turn_on(), "Light powered on.")

    def turn_off(self) -> None:
        self._submit_action(self.client.turn_off(), "Light powered off.")

    def set_color(self, color: tuple[int, int, int]) -> None:
        self.stop_effect(restore_static=False)
        self.state.base_color = color
        self.client.set_base_color(color)
        self.settings.set("base_color", list(color))
        self.state.applied_color = self.client.output_color
        self.settings.set("applied_color", list(self.state.applied_color))
        self.emit_state()
        self._color_timer.start()

    def set_brightness(self, value: int) -> None:
        self.state.brightness = value
        self.client.set_brightness(value)
        self.state.applied_color = self.client.output_color
        self.settings.set("brightness", value)
        self.settings.set("applied_color", list(self.state.applied_color))
        self.emit_state()
        if self.state.active_custom_effect or self.state.active_native_effect:
            return
        self._brightness_timer.start()

    def apply_current_color(self, success_text: str | None = None) -> None:
        target = self.client.output_color
        self.state.applied_color = target
        self.settings.set("applied_color", list(target))
        self._submit_action(self.client.apply_current_color(), success_text or "Applied color.")

    def stop_effect(self, restore_static: bool = True) -> None:
        self.state.active_native_effect = None
        self.state.active_custom_effect = None
        if self._custom_effect_future and not self._custom_effect_future.done():
            self._custom_effect_future.cancel()
        self._custom_effect_future = None
        self.emit_state()
        if restore_static and self.client.is_connected:
            self.apply_current_color("Returned to static color.")

    def native_effect_options(self) -> list[tuple[str, str]]:
        return [(effect_id, label) for effect_id, label, _ in NATIVE_EFFECTS]

    def custom_effect_options(self) -> list[tuple[str, str]]:
        return CUSTOM_EFFECTS

    # --------------------------------------------------------- BLE callbacks

    def _ble_disconnected_callback(self, _client) -> None:
        """Runs in the asyncio thread — relay to Qt via signal."""
        self._ble_dropped.emit()

    def _on_device_dropped(self) -> None:
        """Handles unexpected BLE disconnection on the Qt main thread."""
        if self._shutting_down or self._suppress_ble_errors:
            return
        self._brightness_timer.stop()
        self._color_timer.stop()
        self.state.connected = False
        self.state.device_name = "Not connected"
        self.state.device_address = None
        self.set_status("Device disconnected unexpectedly.")

    # ----------------------------------------------------------- async tasks

    async def _do_reset_and_reconnect(self, address: str) -> str:
        from .bluetooth_reset import reset_ble_device, reset_bluetooth_radio
        await self.client.disconnect()
        # Try targeted device reset first — fast and non-disruptive
        ok = await reset_ble_device(address)
        if not ok:
            # Fall back to full radio toggle
            ok = await reset_bluetooth_radio()
            if not ok:
                raise RuntimeError(
                    "Bluetooth reset failed.\n\n"
                    "Try toggling Bluetooth manually in Windows Settings → Bluetooth & devices."
                )
        await asyncio.sleep(1.5)
        return address

    def _apply_pending_brightness(self) -> None:
        if not self.client.is_connected:
            return
        if self.state.active_custom_effect or self.state.active_native_effect:
            return
        self.apply_current_color("Brightness updated.")

    def _apply_pending_color(self) -> None:
        if not self.client.is_connected:
            return
        self._submit_action(self.client.apply_current_color(), "Applied color.")

    # --------------------------------------------------------- submit helpers

    def _submit_action(self, coro, success_text: str) -> None:
        future = self.bridge.submit(coro)
        future.add_done_callback(lambda done: self._handle_generic_complete(done, success_text))

    # ------------------------------------------------------------ callbacks

    def _handle_scan_complete(self, future: Future) -> None:
        if self._scan_future is future:
            self._scan_future = None
        self.state.scanning = False
        try:
            devices = future.result()
        except Exception as exc:
            self._report_error(str(exc))
            return
        self.state.devices = devices
        likely = sum(1 for d in devices if is_likely_happylighting(d.name))
        self.set_status(
            f"Found {len(devices)} named BLE devices, {likely} likely HappyLighting-compatible."
        )
        if self.state.saved_device_address and not self.state.connected:
            if any(d.address == self.state.saved_device_address for d in devices):
                self.connect_device(self.state.saved_device_address)
            else:
                self.emit_state()

    def _handle_connect_complete(self, future: Future, device: LightDevice) -> None:
        if self._connect_future is future:
            self._connect_future = None
        try:
            future.result()
        except Exception as exc:
            self._report_error(str(exc))
            return
        self._suppress_ble_errors = False
        self.state.connected = True
        self.state.device_name = device.name
        self.state.device_address = device.address
        self.state.saved_device_address = device.address
        self.state.saved_device_name = device.name
        self.settings.update(
            {"last_device_address": device.address, "last_device_name": device.name}
        )
        self.set_status(f"Connected to {device.name}.")
        self.apply_current_color("Restored saved color.")

    def _start_saved_device_reconnect(self) -> None:
        if self._startup_reconnect_future and not self._startup_reconnect_future.done():
            return
        if self._connect_future and not self._connect_future.done():
            return
        address = self.state.saved_device_address
        if not address:
            self.scan_devices()
            return
        self.state.scanning = True
        self.set_status("Looking for your saved controller...")
        future = self.bridge.submit(self.client.find_saved_device(address, timeout=2.0))
        self._startup_reconnect_future = future
        future.add_done_callback(self._handle_startup_reconnect_complete)

    def _handle_startup_reconnect_complete(self, future: Future) -> None:
        if self._startup_reconnect_future is future:
            self._startup_reconnect_future = None
        try:
            device = future.result()
        except Exception as exc:
            self.state.scanning = False
            self._report_error(str(exc))
            return
        if device is None:
            self.set_status("Saved controller not found yet. Scanning all nearby devices...")
            self.scan_devices()
            return
        self.state.scanning = False
        self.state.devices = [device, *[d for d in self.state.devices if d.address != device.address]]
        self.emit_state()
        self.connect_device(device.address)

    def _handle_disconnect_complete(self, future: Future) -> None:
        try:
            future.result()
        except Exception:
            pass  # errors during intentional disconnect are expected
        self.state.connected = False
        self.state.device_name = "Not connected"
        self.state.device_address = None
        self.set_status("Disconnected.")

    def _handle_reset_complete(self, future: Future) -> None:
        try:
            address = future.result()
        except Exception as exc:
            self._suppress_ble_errors = False
            self._report_error(str(exc))
            return
        self.state.connected = False
        self.state.device_name = "Not connected"
        self.emit_state()
        self.set_status("Connection cleared — reconnecting…")
        self.connect_device(address)

    def _handle_generic_complete(self, future: Future, success_text: str) -> None:
        try:
            future.result()
        except Exception as exc:
            self._report_error(str(exc))
            return
        self.emit_state()
        if success_text:
            self.set_status(success_text)

    def _handle_effect_complete(self, future: Future) -> None:
        if self._shutting_down:
            return
        try:
            future.result()
        except CancelledError:
            return
        except Exception as exc:
            self._report_error(str(exc))
            return
        self.emit_state()

    def _report_error(self, message: str) -> None:
        if self._shutting_down:
            return
        if self._suppress_ble_errors and _is_ble_noise(message):
            self.set_status(f"BLE: {message[:120]}")
            return
        if not self.state.connected and _is_ble_noise(message):
            self.set_status(f"BLE: {message[:120]}")
            return
        self.error_message.emit(message)
        self.set_status(message)

from __future__ import annotations

import asyncio
from typing import Iterable

from bleak import BleakClient, BleakScanner

from .effects import NATIVE_EFFECTS, scale_rgb, speed_to_native
from .models import DeviceStatus, LightDevice

WRITE_CHARACTERISTIC_UUIDS = (
    "0000ffd5-0000-1000-8000-00805f9b34fb",
    "0000ffd9-0000-1000-8000-00805f9b34fb",
    "0000ffe5-0000-1000-8000-00805f9b34fb",
    "0000ffe9-0000-1000-8000-00805f9b34fb",
)

READ_CHARACTERISTIC_UUIDS = (
    "0000ffd0-0000-1000-8000-00805f9b34fb",
    "0000ffd4-0000-1000-8000-00805f9b34fb",
    "0000ffe0-0000-1000-8000-00805f9b34fb",
    "0000ffe4-0000-1000-8000-00805f9b34fb",
)

NAME_PREFIXES = ("triones", "brglight", "dream", "light", "ledble", "qhm")
NATIVE_EFFECT_MAP = {effect_id: mode for effect_id, _label, mode in NATIVE_EFFECTS}


def is_likely_happylighting(name: str | None) -> bool:
    if not name:
        return False
    return name.lower().startswith(NAME_PREFIXES)


def _first_match(values: Iterable[str], candidates: set[str]) -> str | None:
    for value in values:
        if value in candidates:
            return value
    return None


class HappyLightingClient:
    def __init__(self) -> None:
        self._client: BleakClient | None = None
        self._write_uuid: str | None = None
        self._read_uuid: str | None = None
        self._device: LightDevice | None = None
        self._discovered_devices: dict[str, object] = {}
        self._base_color = (255, 140, 66)
        self._brightness = 100

    @property
    def device(self) -> LightDevice | None:
        return self._device

    @property
    def is_connected(self) -> bool:
        return bool(self._client and self._client.is_connected)

    @property
    def output_color(self) -> tuple[int, int, int]:
        return scale_rgb(self._base_color, self._brightness)

    def set_base_color(self, color: tuple[int, int, int]) -> None:
        self._base_color = color

    def set_brightness(self, brightness: int) -> None:
        self._brightness = brightness

    async def scan_devices(self, timeout: float = 5.0) -> list[LightDevice]:
        devices = await BleakScanner.discover(timeout=timeout, return_adv=True)
        results: list[LightDevice] = []
        self._discovered_devices = {}
        for _, (device, adv) in devices.items():
            name = device.name or adv.local_name
            if not name:
                continue
            self._discovered_devices[device.address] = device
            results.append(LightDevice(name=name, address=device.address, rssi=adv.rssi))
        results.sort(
            key=lambda item: (
                0 if is_likely_happylighting(item.name) else 1,
                -(item.rssi or -999),
                item.name.lower(),
                item.address,
            )
        )
        return results

    async def find_saved_device(
        self, address: str, timeout: float = 2.0
    ) -> LightDevice | None:
        found: asyncio.Future[LightDevice | None] = asyncio.get_running_loop().create_future()

        def _callback(device, adv) -> None:
            if device.address.lower() != address.lower():
                return
            name = device.name or adv.local_name or address
            self._discovered_devices[device.address] = device
            if not found.done():
                found.set_result(LightDevice(name=name, address=device.address, rssi=adv.rssi))

        scanner = BleakScanner(detection_callback=_callback)
        await scanner.start()
        try:
            try:
                return await asyncio.wait_for(found, timeout=timeout)
            except TimeoutError:
                return None
        finally:
            await scanner.stop()

    async def connect(self, device: LightDevice, disconnected_callback=None) -> None:
        await self.disconnect()
        bleak_device = self._discovered_devices.get(device.address, device.address)
        client = BleakClient(bleak_device, disconnected_callback=disconnected_callback)
        await client.connect(timeout=20.0)
        services = client.services
        uuids = {characteristic.uuid for characteristic in services.characteristics.values()}
        write_uuid = _first_match(WRITE_CHARACTERISTIC_UUIDS, uuids)
        read_uuid = _first_match(READ_CHARACTERISTIC_UUIDS, uuids)
        if not write_uuid:
            await client.disconnect()
            raise RuntimeError("No supported HappyLighting write characteristic was found.")
        self._client = client
        self._write_uuid = write_uuid
        self._read_uuid = read_uuid
        self._device = device

    async def disconnect(self) -> None:
        if self._client and self._client.is_connected:
            await self._client.disconnect()
        self._client = None
        self._write_uuid = None
        self._read_uuid = None
        self._device = None

    async def turn_on(self) -> None:
        await self._write(bytes((0xCC, 0x23, 0x33)))

    async def turn_off(self) -> None:
        await self._write(bytes((0xCC, 0x24, 0x33)))

    async def set_color(self, red: int, green: int, blue: int) -> None:
        self._base_color = (red, green, blue)
        await self.apply_current_color()

    async def apply_current_color(self) -> None:
        await self.send_raw_color(self.output_color)

    async def send_raw_color(self, color: tuple[int, int, int]) -> None:
        red, green, blue = color
        await self._write(bytes((0x56, red, green, blue, 0x00, 0xF0, 0xAA)))

    async def set_native_effect(self, effect_id: str, speed: int) -> None:
        mode = NATIVE_EFFECT_MAP.get(effect_id)
        if mode is None:
            raise RuntimeError(f"Unknown native effect '{effect_id}'.")
        await self._write(bytes((0xBB, mode, speed_to_native(speed), 0x44)))

    async def get_status(self) -> DeviceStatus | None:
        raw = await self.request_status()
        if not raw or len(raw) < 10:
            return None
        is_on = True if raw[2] == 0x23 else False if raw[2] == 0x24 else None
        brightness = raw[9] if raw[9] > 0 else None
        return DeviceStatus(
            is_on=is_on,
            rgb_color=(raw[6], raw[7], raw[8]),
            brightness=brightness,
            raw=raw,
        )

    async def request_status(self) -> bytes | None:
        if not self._client or not self._read_uuid:
            return None
        future: asyncio.Future[bytes] = asyncio.get_running_loop().create_future()

        def _callback(_: int, data: bytearray) -> None:
            if not future.done():
                future.set_result(bytes(data))

        await self._client.start_notify(self._read_uuid, _callback)
        try:
            await self._write(bytes((0xEF, 0x01, 0x77)))
            return await asyncio.wait_for(future, timeout=5.0)
        finally:
            await self._client.stop_notify(self._read_uuid)

    async def _write(self, payload: bytes) -> None:
        if not self._client or not self._client.is_connected or not self._write_uuid:
            raise RuntimeError("No light is connected.")
        await self._client.write_gatt_char(self._write_uuid, payload)

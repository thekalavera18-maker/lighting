from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(slots=True)
class LightDevice:
    name: str
    address: str
    rssi: int | None = None

    @property
    def display_name(self) -> str:
        parts = [self.name, f"[{self.address}]"]
        if self.rssi is not None:
            parts.append(f"RSSI {self.rssi}")
        return " ".join(parts)


@dataclass(slots=True)
class DeviceStatus:
    is_on: bool | None = None
    rgb_color: tuple[int, int, int] | None = None
    brightness: int | None = None
    raw: bytes | None = None


@dataclass(slots=True)
class AppState:
    devices: list[LightDevice] = field(default_factory=list)
    connected: bool = False
    scanning: bool = False
    device_name: str = "Not connected"
    device_address: str | None = None
    status_text: str = "Ready"
    base_color: tuple[int, int, int] = (255, 140, 66)
    applied_color: tuple[int, int, int] = (255, 140, 66)
    brightness: int = 100
    active_native_effect: str | None = None
    active_custom_effect: str | None = None
    saved_device_address: str | None = None
    saved_device_name: str | None = None

    def as_dict(self) -> dict:
        return {
            "devices": self.devices,
            "connected": self.connected,
            "scanning": self.scanning,
            "device_name": self.device_name,
            "device_address": self.device_address,
            "status_text": self.status_text,
            "base_color": self.base_color,
            "applied_color": self.applied_color,
            "brightness": self.brightness,
            "active_native_effect": self.active_native_effect,
            "active_custom_effect": self.active_custom_effect,
            "saved_device_address": self.saved_device_address,
            "saved_device_name": self.saved_device_name,
        }

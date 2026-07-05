from __future__ import annotations

import colorsys
import math
import time


NATIVE_EFFECTS: list[tuple[str, str, int]] = [
    ("rainbow", "Rainbow", 0x25),
    ("color_cycle", "Color Cycle", 0x26),
    ("pulse", "Pulse", 0x27),
    ("strobe", "Strobe", 0x28),
    ("smooth_fade", "Smooth Fade", 0x2A),
]

CUSTOM_EFFECTS: list[tuple[str, str]] = [
    ("custom_rainbow", "Custom Rainbow"),
    ("two_color_pulse", "Two-Color Pulse"),
    ("palette_cycle", "Palette Cycle"),
]


def clamp_speed(value: int) -> int:
    return max(1, min(255, value))


def speed_to_native(value: int) -> int:
    return clamp_speed(round((max(1, min(value, 100)) / 100.0) * 255))


def scale_rgb(color: tuple[int, int, int], brightness: int) -> tuple[int, int, int]:
    factor = max(1, min(brightness, 100)) / 100.0
    return tuple(max(0, min(255, round(channel * factor))) for channel in color)


def wheel(position: float) -> tuple[int, int, int]:
    red, green, blue = colorsys.hsv_to_rgb(position % 1.0, 1.0, 1.0)
    return round(red * 255), round(green * 255), round(blue * 255)


def pulse_color(
    first: tuple[int, int, int],
    second: tuple[int, int, int],
    speed: int,
    now: float | None = None,
) -> tuple[int, int, int]:
    tick = time.monotonic() if now is None else now
    freq = 0.35 + (speed / 100.0) * 1.4
    ratio = (math.sin(tick * freq * math.tau) + 1.0) / 2.0
    return tuple(round(a + (b - a) * ratio) for a, b in zip(first, second))


def palette_cycle(palette: list[tuple[int, int, int]], speed: int, now: float | None = None) -> tuple[int, int, int]:
    tick = time.monotonic() if now is None else now
    if not palette:
        return 255, 255, 255
    duration = max(0.4, 3.4 - (speed / 100.0) * 2.8)
    phase = (tick / duration) % len(palette)
    index = int(phase)
    next_index = (index + 1) % len(palette)
    ratio = phase - index
    current = palette[index]
    nxt = palette[next_index]
    return tuple(round(a + (b - a) * ratio) for a, b in zip(current, nxt))


def custom_effect_color(
    effect_id: str,
    speed: int,
    base_color: tuple[int, int, int],
    brightness: int,
    now: float | None = None,
) -> tuple[int, int, int]:
    tick = time.monotonic() if now is None else now
    if effect_id == "custom_rainbow":
        freq = 0.02 + (speed / 100.0) * 0.18
        color = wheel(tick * freq)
    elif effect_id == "two_color_pulse":
        color = pulse_color(base_color, (255, 255, 255), speed, tick)
    else:
        palette = [
            base_color,
            (255, 56, 100),
            (255, 193, 7),
            (0, 200, 180),
            (112, 88, 255),
        ]
        color = palette_cycle(palette, speed, tick)
    return scale_rgb(color, brightness)

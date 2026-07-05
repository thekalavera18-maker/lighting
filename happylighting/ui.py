from __future__ import annotations

import colorsys
import math

from PySide6.QtCore import Qt, QPointF, Signal
from PySide6.QtGui import (
    QBrush,
    QColor,
    QCloseEvent,
    QConicalGradient,
    QPainter,
    QPen,
    QRadialGradient,
)
from PySide6.QtWidgets import (
    QComboBox,
    QFrame,
    QGridLayout,
    QGraphicsDropShadowEffect,
    QHBoxLayout,
    QLabel,
    QMainWindow,
    QMessageBox,
    QPushButton,
    QSlider,
    QVBoxLayout,
    QWidget,
)

from .controller import is_likely_happylighting
from .icons import create_app_icon
from .service import HappyLightingService


APP_STYLESHEET = """
QMainWindow, QWidget {
    background: #0d1017;
    color: #dde3ec;
    font-family: "Segoe UI";
    font-size: 10pt;
}
QLabel { background: transparent; }
QLabel#SecLabel {
    font-size: 7pt; font-weight: 700;
    color: #3e4e62; letter-spacing: 2px;
}
QLabel#Muted { color: #4e6070; font-size: 9pt; }
QComboBox {
    background: #151c27;
    border: 1px solid #1e2a3a;
    border-radius: 7px;
    padding: 6px 10px;
    font-size: 9pt;
    color: #c8d4e0;
}
QComboBox::drop-down { border: none; width: 16px; }
QComboBox QAbstractItemView {
    background: #151c27;
    border: 1px solid #1e2a3a;
    selection-background-color: rgba(255,142,94,0.2);
}
QPushButton {
    background: #151c27;
    color: #b0bec8;
    border: 1px solid #1e2a3a;
    border-radius: 7px;
    padding: 6px 12px;
    font-size: 9pt;
    font-weight: 600;
}
QPushButton:hover { background: #1c2535; color: #dde3ec; border-color: #2a3a50; }
QPushButton:pressed { background: #111820; }
QPushButton:checked {
    background: #ff8e5e;
    color: #1a100a;
    border: none;
    font-weight: 700;
}
QPushButton[accent="true"] {
    background: #ff8e5e; color: #1a100a;
    border: none; font-weight: 700;
}
QPushButton[accent="true"]:hover { background: #ffab7e; }
QPushButton[danger="true"] {
    background: rgba(200,60,60,0.10);
    color: #e06060;
    border: 1px solid rgba(200,60,60,0.20);
}
QPushButton[danger="true"]:hover { background: rgba(200,60,60,0.18); }
QSlider::groove:horizontal {
    background: #151c27; border-radius: 3px; height: 6px;
}
QSlider::sub-page:horizontal {
    background: #ff8e5e; border-radius: 3px;
}
QSlider::handle:horizontal {
    background: #ffeedd; width: 14px; margin: -4px 0; border-radius: 7px;
}
"""


def _divider() -> QFrame:
    f = QFrame()
    f.setFrameShape(QFrame.Shape.HLine)
    f.setStyleSheet("background: #161e2a; max-height: 1px; margin: 0;")
    return f


def _sec(text: str) -> QLabel:
    lbl = QLabel(text)
    lbl.setObjectName("SecLabel")
    return lbl


def _btn(text: str, accent=False, danger=False) -> QPushButton:
    b = QPushButton(text)
    if accent:
        b.setProperty("accent", True)
    elif danger:
        b.setProperty("danger", True)
    if accent or danger:
        b.style().unpolish(b)
        b.style().polish(b)
    return b


# ── Color wheel ───────────────────────────────────────────────────────────────

class ColorWheel(QWidget):
    color_picked = Signal(object)

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self.setFixedSize(220, 220)
        self.setCursor(Qt.CursorShape.CrossCursor)
        self._hue = 30.0 / 360.0
        self._sat = 0.85

    def set_color(self, r: int, g: int, b: int) -> None:
        h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
        self._hue = h
        self._sat = s if v > 0 else self._sat  # keep sat when color is black
        self.update()

    def _r(self) -> float:
        return min(self.width(), self.height()) / 2.0 - 3

    def _c(self) -> tuple[float, float]:
        return self.width() / 2.0, self.height() / 2.0

    def paintEvent(self, _) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        cx, cy = self._c()
        r = self._r()

        hg = QConicalGradient(cx, cy, 0.0)
        for i in range(13):
            h = i / 12.0
            rr, gg, bb = colorsys.hsv_to_rgb(h, 1.0, 1.0)
            hg.setColorAt(h, QColor(round(rr * 255), round(gg * 255), round(bb * 255)))
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(QBrush(hg))
        p.drawEllipse(QPointF(cx, cy), r, r)

        sg = QRadialGradient(cx, cy, r)
        sg.setColorAt(0.0, QColor(255, 255, 255, 255))
        sg.setColorAt(1.0, QColor(255, 255, 255, 0))
        p.setBrush(QBrush(sg))
        p.drawEllipse(QPointF(cx, cy), r, r)

        angle = self._hue * math.tau
        mx = cx + math.cos(angle) * self._sat * r
        my = cy - math.sin(angle) * self._sat * r
        p.setBrush(Qt.BrushStyle.NoBrush)
        p.setPen(QPen(QColor("#111"), 2.5))
        p.drawEllipse(QPointF(mx, my), 8.0, 8.0)
        p.setPen(QPen(QColor("#fff"), 1.5))
        p.drawEllipse(QPointF(mx, my), 7.0, 7.0)

    def _pick(self, x: float, y: float) -> None:
        cx, cy = self._c()
        r = self._r()
        dx, dy = x - cx, -(y - cy)
        dist = math.hypot(dx, dy)
        self._sat = min(dist / r, 1.0)
        if dist > 8:  # dead zone — ignore hue near center where it's unstable
            self._hue = (math.atan2(dy, dx) / math.tau) % 1.0
        rr, gg, bb = colorsys.hsv_to_rgb(self._hue, self._sat, 1.0)
        self.color_picked.emit((round(rr * 255), round(gg * 255), round(bb * 255)))
        self.update()

    def mousePressEvent(self, e) -> None:
        self._pick(e.position().x(), e.position().y())

    def mouseMoveEvent(self, e) -> None:
        if e.buttons() & Qt.MouseButton.LeftButton:
            self._pick(e.position().x(), e.position().y())


class RgbSliders(QWidget):
    color_picked = Signal(object)

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        layout = QGridLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setHorizontalSpacing(10)
        layout.setVerticalSpacing(8)

        self._sliders: dict[str, QSlider] = {}
        self._value_labels: dict[str, QLabel] = {}
        channel_styles = {
            "R": "#ff6b6b",
            "G": "#56d364",
            "B": "#5da9ff",
        }
        for row, channel in enumerate(("R", "G", "B")):
            label = QLabel(channel)
            label.setStyleSheet(f"color: {channel_styles[channel]}; font-weight: 700;")
            value = QLabel("255")
            value.setObjectName("Muted")
            value.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)

            slider = QSlider(Qt.Orientation.Horizontal)
            slider.setRange(0, 255)
            slider.setStyleSheet(
                "QSlider::sub-page:horizontal {"
                f" background: {channel_styles[channel]}; border-radius: 3px;"
                "}"
            )
            slider.valueChanged.connect(self._emit_color)

            self._sliders[channel] = slider
            self._value_labels[channel] = value

            layout.addWidget(label, row, 0)
            layout.addWidget(slider, row, 1)
            layout.addWidget(value, row, 2)

        self.set_color(255, 140, 66)

    def set_color(self, r: int, g: int, b: int) -> None:
        for channel, value in zip(("R", "G", "B"), (r, g, b)):
            slider = self._sliders[channel]
            slider.blockSignals(True)
            slider.setValue(value)
            slider.blockSignals(False)
            self._value_labels[channel].setText(str(value))

    def _emit_color(self) -> None:
        color = tuple(self._sliders[channel].value() for channel in ("R", "G", "B"))
        for channel, value in zip(("R", "G", "B"), color):
            self._value_labels[channel].setText(str(value))
        self.color_picked.emit(color)


# ── Main window ───────────────────────────────────────────────────────────────

class MainWindow(QMainWindow):
    def __init__(self, service: HappyLightingService) -> None:
        super().__init__()
        self.service = service
        self.current_state: dict = {}
        self._error_dialog_open = False
        self._last_device_addresses: list[str] = []

        self.setWindowTitle("HappyLighting")
        self.setMinimumWidth(390)
        self.setWindowIcon(create_app_icon())
        self.setStyleSheet(APP_STYLESHEET)

        self._build_ui()

        self.service.state_changed.connect(self._apply_state)
        self.service.error_message.connect(self._show_error)

    # ------------------------------------------------------------------ build

    def _build_ui(self) -> None:
        root = QWidget()
        layout = QVBoxLayout(root)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        # ── Header pill row ───────────────────────────────────────────────────
        header = QWidget()
        header.setStyleSheet("background: #0a0d13;")
        hl = QHBoxLayout(header)
        hl.setContentsMargins(18, 12, 18, 12)
        self._conn_pill = QLabel("● Not connected")
        self._conn_pill.setStyleSheet(self._pill_style("#3e4e62", "transparent"))
        hl.addWidget(self._conn_pill)
        hl.addStretch()
        self.saved_label = QLabel("Saved: none")
        self.saved_label.setObjectName("Muted")
        hl.addWidget(self.saved_label)
        layout.addWidget(header)
        layout.addWidget(_divider())

        # ── Device section ────────────────────────────────────────────────────
        device = QWidget()
        dl = QVBoxLayout(device)
        dl.setContentsMargins(18, 14, 18, 14)
        dl.setSpacing(8)
        dl.addWidget(_sec("DEVICE"))

        combo_row = QHBoxLayout()
        combo_row.setSpacing(6)
        self.device_combo = QComboBox()
        self.scan_button = _btn("Scan")
        self.scan_button.setFixedWidth(62)
        combo_row.addWidget(self.device_combo, 1)
        combo_row.addWidget(self.scan_button)
        dl.addLayout(combo_row)

        action_row = QHBoxLayout()
        action_row.setSpacing(6)
        self.connect_button    = _btn("Connect", accent=True)
        self.disconnect_button = _btn("Disconnect")
        self.reset_button      = _btn("Fix Connection", danger=True)
        action_row.addWidget(self.connect_button)
        action_row.addWidget(self.disconnect_button)
        action_row.addWidget(self.reset_button)
        dl.addLayout(action_row)
        layout.addWidget(device)
        layout.addWidget(_divider())

        # ── Color section ─────────────────────────────────────────────────────
        color = QWidget()
        cl = QVBoxLayout(color)
        cl.setContentsMargins(18, 16, 18, 16)
        cl.setSpacing(10)
        cl.addWidget(_sec("COLOR"))

        mode_row = QHBoxLayout()
        mode_row.setSpacing(6)
        self.wheel_mode_button = _btn("Wheel")
        self.rgb_mode_button = _btn("RGB")
        self.wheel_mode_button.setCheckable(True)
        self.rgb_mode_button.setCheckable(True)
        mode_row.addWidget(self.wheel_mode_button)
        mode_row.addWidget(self.rgb_mode_button)
        mode_row.addStretch()
        cl.addLayout(mode_row)

        self.color_wheel = ColorWheel()
        self._wheel_glow = QGraphicsDropShadowEffect()
        self._wheel_glow.setBlurRadius(42)
        self._wheel_glow.setOffset(0, 0)
        self._wheel_glow.setColor(QColor(255, 140, 66, 140))
        self.color_wheel.setGraphicsEffect(self._wheel_glow)
        self.rgb_sliders = RgbSliders()

        self.wheel_picker = QWidget()
        wheel_row = QHBoxLayout(self.wheel_picker)
        wheel_row.setContentsMargins(0, 0, 0, 0)
        wheel_row.addStretch()
        wheel_row.addWidget(self.color_wheel)
        wheel_row.addStretch()
        cl.addWidget(self.wheel_picker)

        self.rgb_picker = QWidget()
        rgb_layout = QVBoxLayout(self.rgb_picker)
        rgb_layout.setContentsMargins(0, 0, 0, 0)
        rgb_layout.addWidget(self.rgb_sliders)
        cl.addWidget(self.rgb_picker)
        self._set_color_picker_mode("wheel")

        # swatch + brightness on same row
        self.preview = QLabel()
        self.preview.setFixedHeight(26)
        self.preview.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.preview.setStyleSheet("border-radius: 5px; font-size: 8pt; font-weight: 700;")
        cl.addWidget(self.preview)

        cl.addWidget(_sec("BRIGHTNESS"))
        self.brightness_slider = QSlider(Qt.Orientation.Horizontal)
        self.brightness_slider.setRange(1, 100)
        self.brightness_slider.setTracking(True)
        cl.addWidget(self.brightness_slider)

        power_row = QHBoxLayout()
        power_row.setSpacing(8)
        self.turn_on_button  = _btn("Power On", accent=True)
        self.turn_off_button = _btn("Power Off")
        power_row.addWidget(self.turn_on_button)
        power_row.addWidget(self.turn_off_button)
        cl.addLayout(power_row)
        layout.addWidget(color)
        layout.addWidget(_divider())

        # ── Status bar ────────────────────────────────────────────────────────
        status = QWidget()
        status.setStyleSheet("background: #0a0d13;")
        sl = QHBoxLayout(status)
        sl.setContentsMargins(18, 10, 18, 10)
        self.status_label = QLabel("Ready")
        self.status_label.setObjectName("Muted")
        self.status_label.setWordWrap(True)
        sl.addWidget(self.status_label)
        layout.addWidget(status)

        self.setCentralWidget(root)

        # Signals
        self.scan_button.clicked.connect(self.service.scan_devices)
        self.connect_button.clicked.connect(self._connect_selected)
        self.disconnect_button.clicked.connect(self.service.disconnect_device)
        self.reset_button.clicked.connect(self.service.reset_and_reconnect)
        self.turn_on_button.clicked.connect(self.service.turn_on)
        self.turn_off_button.clicked.connect(self.service.turn_off)
        self.wheel_mode_button.clicked.connect(lambda: self._set_color_picker_mode("wheel"))
        self.rgb_mode_button.clicked.connect(lambda: self._set_color_picker_mode("rgb"))
        self.color_wheel.color_picked.connect(self.service.set_color)
        self.rgb_sliders.color_picked.connect(self.service.set_color)
        self.brightness_slider.valueChanged.connect(self.service.set_brightness)

    # ------------------------------------------------------------ state sync

    @staticmethod
    def _pill_style(color: str, bg: str) -> str:
        return (
            f"background: {bg}; border: 1px solid {color}55;"
            f" border-radius: 10px; padding: 3px 11px;"
            f" font-size: 8.5pt; color: {color};"
        )

    def _set_color_picker_mode(self, mode: str) -> None:
        show_wheel = mode == "wheel"
        self.wheel_mode_button.setChecked(show_wheel)
        self.rgb_mode_button.setChecked(not show_wheel)
        self.wheel_picker.setVisible(show_wheel)
        self.rgb_picker.setVisible(not show_wheel)

    def _apply_state(self, state: dict) -> None:
        self.current_state = state
        connected = state["connected"]
        scanning  = state["scanning"]

        if scanning:
            self._conn_pill.setText("● Scanning…")
            self._conn_pill.setStyleSheet(self._pill_style("#f7c948", "rgba(247,201,72,0.08)"))
        elif connected:
            self._conn_pill.setText(f"● {state['device_name']}")
            self._conn_pill.setStyleSheet(self._pill_style("#4de3a0", "rgba(77,227,160,0.08)"))
        else:
            self._conn_pill.setText("● Not connected")
            self._conn_pill.setStyleSheet(self._pill_style("#3e4e62", "transparent"))

        saved = state["saved_device_name"] or "none"
        self.saved_label.setText(f"Saved: {saved}")
        self.status_label.setText(state["status_text"])

        self.brightness_slider.blockSignals(True)
        self.brightness_slider.setValue(state["brightness"])
        self.brightness_slider.blockSignals(False)

        new_addrs = [d.address for d in state["devices"]]
        if new_addrs != self._last_device_addresses:
            self._last_device_addresses = new_addrs
            self.device_combo.blockSignals(True)
            self.device_combo.clear()
            for device in state["devices"]:
                tag = "HL" if is_likely_happylighting(device.name) else "BLE"
                self.device_combo.addItem(f"[{tag}] {device.display_name}", device.address)
            saved_addr = state["saved_device_address"]
            if saved_addr:
                idx = self.device_combo.findData(saved_addr)
                if idx >= 0:
                    self.device_combo.setCurrentIndex(idx)
            elif self.device_combo.count() > 0:
                self.device_combo.setCurrentIndex(0)
            self.device_combo.blockSignals(False)

        r, g, b = state["applied_color"]
        br, bg_c, bb = state["base_color"]
        self.color_wheel.set_color(br, bg_c, bb)
        self.rgb_sliders.set_color(br, bg_c, bb)
        self._wheel_glow.setColor(QColor(br, bg_c, bb, 160))

        luma = 0.299 * r + 0.587 * g + 0.114 * b
        tc = "#0d1017" if luma > 140 else "#dde3ec"
        self.preview.setStyleSheet(
            f"background: rgb({r},{g},{b}); border-radius: 5px;"
            f" color: {tc}; font-size: 8pt; font-weight: 700;"
        )
        self.preview.setText(f"#{r:02X}{g:02X}{b:02X}")


    # ---------------------------------------------------------- interactions

    def _selected_address(self) -> str | None:
        return self.device_combo.currentData()

    def _connect_selected(self) -> None:
        self.service.connect_device(self._selected_address())

    def _show_error(self, message: str) -> None:
        if self._error_dialog_open:
            return
        self._error_dialog_open = True
        QMessageBox.critical(self, "HappyLighting", message)
        self._error_dialog_open = False

    def closeEvent(self, event: QCloseEvent) -> None:
        super().closeEvent(event)

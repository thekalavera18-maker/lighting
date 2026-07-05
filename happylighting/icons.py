from __future__ import annotations

from PySide6.QtCore import QPointF, Qt
from PySide6.QtGui import QColor, QIcon, QLinearGradient, QPainter, QPainterPath, QPen, QPixmap


def create_app_icon() -> QIcon:
    pixmap = QPixmap(128, 128)
    pixmap.fill(Qt.GlobalColor.transparent)

    painter = QPainter(pixmap)
    painter.setRenderHint(QPainter.RenderHint.Antialiasing)

    gradient = QLinearGradient(0, 0, 128, 128)
    gradient.setColorAt(0.0, QColor("#ff7b54"))
    gradient.setColorAt(0.55, QColor("#ffd166"))
    gradient.setColorAt(1.0, QColor("#4de3c1"))
    painter.setBrush(gradient)
    painter.setPen(Qt.PenStyle.NoPen)
    painter.drawRoundedRect(10, 10, 108, 108, 28, 28)

    path = QPainterPath()
    path.moveTo(QPointF(64, 28))
    path.cubicTo(QPointF(39, 47), QPointF(39, 77), QPointF(64, 100))
    path.cubicTo(QPointF(89, 77), QPointF(89, 47), QPointF(64, 28))
    painter.setBrush(QColor(18, 21, 31, 220))
    painter.drawPath(path)

    painter.setPen(QPen(QColor("#fff6e9"), 7, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap))
    painter.drawLine(64, 40, 64, 88)
    painter.drawArc(34, 42, 60, 44, 0, 180 * 16)
    painter.end()

    return QIcon(pixmap)

from __future__ import annotations

import sys

from PySide6.QtCore import QTimer
from PySide6.QtWidgets import QApplication

from .service import HappyLightingService
from .ui import MainWindow

try:
    import pyi_splash
except ImportError:
    pyi_splash = None


def _update_splash(text: str) -> None:
    if pyi_splash and pyi_splash.is_alive():
        pyi_splash.update_text(text)


def _close_splash() -> None:
    if pyi_splash and pyi_splash.is_alive():
        pyi_splash.close()


def main() -> None:
    _update_splash("Loading HappyLighting...")
    app = QApplication(sys.argv)
    _update_splash("Starting interface...")
    service = HappyLightingService()
    app.aboutToQuit.connect(service.shutdown)
    window = MainWindow(service)
    window.show()
    QTimer.singleShot(0, service.startup)
    QTimer.singleShot(150, _close_splash)
    sys.exit(app.exec())


if __name__ == "__main__":
    main()

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent


def main() -> None:
    command = [
        sys.executable,
        "-m",
        "PyInstaller",
        "--noconfirm",
        "--clean",
        "--onefile",
        "--windowed",
        "--splash",
        str(ROOT / "assets" / "splash.png"),
        "--name",
        "HappyLighting",
        "--icon",
        str(ROOT / "assets" / "app.ico"),
        str(ROOT / "run.pyw"),
    ]
    subprocess.run(command, cwd=ROOT, check=True)


if __name__ == "__main__":
    main()

from __future__ import annotations

import json
import os
from pathlib import Path


LEGACY_SETTINGS_PATH = Path(__file__).resolve().parent.parent / "lighting-settings.json"
APP_DIR = Path(os.environ.get("APPDATA", Path.home())) / "HappyLighting"
SETTINGS_PATH = APP_DIR / "settings.json"


class SettingsStore:
    def __init__(self) -> None:
        APP_DIR.mkdir(parents=True, exist_ok=True)
        self.path = SETTINGS_PATH
        self.data = self._load()

    def _load(self) -> dict:
        if self.path.exists():
            return self._read_json(self.path)
        if LEGACY_SETTINGS_PATH.exists():
            data = self._read_json(LEGACY_SETTINGS_PATH)
            self._write_json(data)
            return data
        return {}

    def _read_json(self, path: Path) -> dict:
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return {}

    def _write_json(self, data: dict) -> None:
        self.path.write_text(json.dumps(data, indent=2), encoding="utf-8")

    def save(self) -> None:
        self._write_json(self.data)

    def get(self, key: str, default=None):
        return self.data.get(key, default)

    def set(self, key: str, value) -> None:
        self.data[key] = value
        self.save()

    def update(self, values: dict) -> None:
        self.data.update(values)
        self.save()

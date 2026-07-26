"""Session directory layout shared by the collector and the PowerShell driver.

The PowerShell side owns session.json for HCI extraction (M1); the collector
only adds its own keys so both can write to the same session without clobbering
each other.
"""

import json
import os
from datetime import datetime, timezone


class Session:
    def __init__(self, output_root, session_id):
        self.session_id = session_id
        self.root = os.path.join(output_root, session_id)
        self.raw = os.path.join(self.root, "raw")
        self.derived = os.path.join(self.root, "derived")
        self.sanitized = os.path.join(self.root, "sanitized")
        for path in (self.root, self.raw, self.derived, self.sanitized):
            os.makedirs(path, exist_ok=True)

    @property
    def session_file(self):
        return os.path.join(self.root, "session.json")

    @property
    def events_path(self):
        return os.path.join(self.raw, "frida-events.jsonl")

    @property
    def payloads_path(self):
        return os.path.join(self.raw, "frida-events.bin")

    def load(self):
        if not os.path.exists(self.session_file):
            return {}
        with open(self.session_file, "r", encoding="utf-8") as handle:
            return json.load(handle)

    def merge(self, updates):
        """Read-modify-write so HCI keys written by capture.ps1 survive."""
        data = self.load()
        data.update(updates)
        with open(self.session_file, "w", encoding="utf-8") as handle:
            json.dump(data, handle, ensure_ascii=False, indent=2)
        return data


def utc_now_iso():
    return datetime.now(timezone.utc).isoformat()

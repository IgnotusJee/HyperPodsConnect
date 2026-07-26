"""Event sink for the capture agent.

Payloads never enter the JSON. They are appended verbatim to a side blob and
referenced by offset, so the bytes stay exactly as the app saw them: no hex
round-trip, no encoding surprises, and no cost for large GATT or RFCOMM buffers.
"""

import hashlib
import json


class EventWriter:
    def __init__(self, events_path, payloads_path):
        self._events = open(events_path, "w", encoding="utf-8", newline="\n")
        self._payloads = open(payloads_path, "wb")
        self._offset = 0
        self.event_count = 0
        self.payload_bytes = 0
        self.kind_counts = {}

    def write(self, event, payload=None):
        if payload:
            event = dict(event)
            event["payloadOffset"] = self._offset
            event["payloadBytes"] = len(payload)
            event["payloadSha256"] = hashlib.sha256(payload).hexdigest()
            self._payloads.write(payload)
            # Flush both streams on every event. A crash mid-session must leave
            # the JSONL and the blob consistent with each other, and the
            # self-test reads the blob back through a separate handle while the
            # session is still open.
            self._payloads.flush()
            self._offset += len(payload)
            self.payload_bytes += len(payload)

        kind = event.get("kind", "unknown")
        self.kind_counts[kind] = self.kind_counts.get(kind, 0) + 1
        self.event_count += 1

        self._events.write(json.dumps(event, ensure_ascii=False) + "\n")
        self._events.flush()

    def close(self):
        self._events.close()
        self._payloads.close()

    def summary(self):
        return {
            "eventCount": self.event_count,
            "payloadBytes": self.payload_bytes,
            "kindCounts": dict(sorted(self.kind_counts.items())),
        }

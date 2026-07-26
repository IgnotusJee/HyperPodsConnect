"""Correlates Frida transport events against the HCI capture.

The point is to prove that what the app handed to the Bluetooth API is exactly
what left the radio. A Frida-only trace shows intent; an HCI-only trace shows
bytes without semantics. A payload that appears byte-identical in both, in the
same direction, is the evidence the capture plan requires before a fixture may
claim device confirmation.

Matching is on the payload bytes first, not on time. The btsnoop clock on this
ROM is skewed by a whole UTC offset, so time is derived from the matches rather
than assumed: if the per-match offsets cluster tightly, that both confirms the
pairing and recovers the skew. A wide spread means the match is coincidental.
"""

import argparse
import json
import os
import statistics
import subprocess
import sys

DEFAULT_TSHARK = r"C:\Program Files\Wireshark\tshark.exe"

def parse_direction(value):
    """hci_h4.direction renders as 0x00 / 0x01, not 0 / 1.

    Comparing the raw string against "0" silently classifies every frame as
    received and drops the match rate to zero, so parse it numerically.
    """
    token = value.split(",")[0].strip()
    if not token:
        return None
    return "tx" if int(token, 16) == 0 else "rx"


def load_frida_events(events_path, payloads_path):
    with open(events_path, "r", encoding="utf-8") as handle:
        events = [json.loads(line) for line in handle if line.strip()]
    if not os.path.exists(payloads_path):
        return events, {}

    with open(payloads_path, "rb") as blob:
        raw = blob.read()

    payloads = {}
    for event in events:
        if "payloadOffset" in event:
            start = event["payloadOffset"]
            payloads[event["seq"]] = raw[start:start + event["payloadBytes"]]
    return events, payloads


def extract_hci_rfcomm(tshark, capture, handles):
    handle_filter = " || ".join(f"bthci_acl.chandle == {h}" for h in handles)
    display = f"btrfcomm && data && ({handle_filter})"
    result = subprocess.run(
        [tshark, "-r", capture, "-Y", display, "-T", "fields",
         "-e", "frame.number", "-e", "frame.time_epoch",
         "-e", "hci_h4.direction", "-e", "data.data"],
        capture_output=True, text=True, check=True,
    )

    frames = []
    for line in result.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) < 4 or not parts[3]:
            continue
        # A single frame can carry several data fields; tshark joins them by ','.
        for chunk in parts[3].split(","):
            payload = bytes.fromhex(chunk.replace(":", ""))
            if not payload:
                continue
            direction = parse_direction(parts[2])
            if direction is None:
                continue
            frames.append({
                "frame": int(parts[0]),
                "epoch": float(parts[1]),
                "direction": direction,
                "payload": payload,
            })
    return frames


def stream_of(items, direction, key):
    """Concatenates one direction into a single byte stream, in order."""
    return b"".join(item[key] for item in items if item["direction"] == direction)


def correlate(events, payloads, hci_frames):
    index = {}
    for frame in hci_frames:
        index.setdefault((frame["direction"], frame["payload"]), []).append(frame)

    candidates_for = []
    unmatched = []
    for event in events:
        if event.get("kind") != "spp.data" or event["seq"] not in payloads:
            continue
        payload = payloads[event["seq"]]
        direction = event.get("direction")
        candidates = index.get((direction, payload), [])
        if candidates:
            candidates_for.append((event, payload, direction, candidates))
        else:
            unmatched.append({
                "seq": event["seq"],
                "direction": direction,
                "bytes": len(payload),
                "payloadHex": payload.hex(),
            })

    # The same command can be sent more than once across a capture, so an
    # identical payload may have several HCI candidates. Anchor on the events
    # that are unambiguous, take their offset as the reference, then resolve the
    # ambiguous ones to the candidate nearest that offset. Picking the first
    # candidate instead silently pairs an event with a frame from an unrelated
    # earlier run and inflates the spread past any useful threshold.
    anchors = [
        c[3][0]["epoch"] - c[0]["wallMs"] / 1000.0
        for c in candidates_for
        if len(c[3]) == 1
    ]
    reference = statistics.median(anchors) if anchors else 0.0

    matches = []
    for event, payload, direction, frames in candidates_for:
        event_epoch = event["wallMs"] / 1000.0
        frame = min(frames, key=lambda f: abs((f["epoch"] - event_epoch) - reference))
        matches.append({
            "seq": event["seq"],
            "direction": direction,
            "bytes": len(payload),
            "hciFrame": frame["frame"],
            "candidateCount": len(frames),
            "offsetSeconds": frame["epoch"] - event_epoch,
            "payloadHex": payload.hex(),
        })
    return matches, unmatched


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session", required=True, help="会话目录")
    parser.add_argument("--capture", help="btsnoop 文件；默认取会话 raw/ 下第一个")
    parser.add_argument("--handles", default="6", help="逗号分隔的连接句柄十进制值")
    parser.add_argument("--tshark", default=DEFAULT_TSHARK)
    parser.add_argument("--json", dest="json_path")
    args = parser.parse_args()

    raw_dir = os.path.join(args.session, "raw")
    events_path = os.path.join(raw_dir, "frida-events.jsonl")
    payloads_path = os.path.join(raw_dir, "frida-events.bin")
    if not os.path.exists(events_path):
        raise SystemExit(f"未找到 Frida 事件：{events_path}")

    capture = args.capture
    if not capture:
        candidates = [f for f in os.listdir(raw_dir) if f.startswith("btsnoop") and f.endswith(".log")]
        if not candidates:
            raise SystemExit(f"{raw_dir} 下没有 btsnoop 捕获")
        capture = os.path.join(raw_dir, candidates[0])

    handles = [int(h.strip()) for h in args.handles.split(",") if h.strip()]

    events, payloads = load_frida_events(events_path, payloads_path)
    hci_frames = extract_hci_rfcomm(args.tshark, capture, handles)
    matches, unmatched = correlate(events, payloads, hci_frames)

    total = len(matches) + len(unmatched)
    tx_matches = [m for m in matches if m["direction"] == "tx"]

    print(f"Frida spp.data 事件 : {total}")
    print(f"HCI RFCOMM 数据帧   : {len(hci_frames)}")
    print(f"字节级匹配          : {len(matches)}（TX {len(tx_matches)}，RX {len(matches) - len(tx_matches)}）")
    print(f"未匹配              : {len(unmatched)}")

    offsets = [m["offsetSeconds"] for m in matches]
    spread = None
    if offsets:
        median = statistics.median(offsets)
        spread = max(offsets) - min(offsets)
        print(f"时间偏移中位数      : {median:.1f} 秒（{median / 3600:.2f} 小时）")
        print(f"偏移离散度          : {spread:.3f} 秒")
        if spread < 5.0:
            print("偏移聚集紧密：匹配是真实配对，不是巧合")
        else:
            print("偏移离散过大：匹配可能不可靠，需人工复核", file=sys.stderr)

    if matches:
        print("\n样例匹配（TX）：")
        for match in tx_matches[:5]:
            print(f"  seq={match['seq']:3d} frame={match['hciFrame']:6d} "
                  f"{match['bytes']:3d}B  {match['payloadHex'][:48]}")

    if unmatched:
        print("\n未匹配样例：")
        for item in unmatched[:5]:
            print(f"  seq={item['seq']:3d} {item['direction']} {item['bytes']:3d}B "
                  f"{item['payloadHex'][:48]}")

    # Frame-level matching cannot succeed on the receive path: the app reads the
    # socket in arbitrary chunks, so one HCI frame routinely arrives as several
    # read() results. The plan allows equality "after link reassembly", which at
    # this layer means comparing the concatenated per-direction byte streams.
    frida_data = [
        {"direction": e.get("direction"), "payload": payloads[e["seq"]]}
        for e in events
        if e.get("kind") == "spp.data" and e["seq"] in payloads
    ]
    print("\n流级比对（分片重组后）：")
    stream_results = {}
    for direction in ("tx", "rx"):
        frida_stream = stream_of(frida_data, direction, "payload")
        hci_stream = stream_of(hci_frames, direction, "payload")
        if not frida_stream and not hci_stream:
            continue
        if frida_stream == hci_stream:
            verdict = "完全一致"
        elif frida_stream and hci_stream.endswith(frida_stream):
            verdict = "Frida 流是 HCI 流的后缀（attach 晚于连接建立）"
        elif frida_stream and frida_stream in hci_stream:
            verdict = "Frida 流是 HCI 流的连续子串"
        else:
            verdict = "不一致"
        stream_results[direction] = {
            "fridaBytes": len(frida_stream),
            "hciBytes": len(hci_stream),
            "verdict": verdict,
        }
        print(f"  {direction}: Frida {len(frida_stream)}B / HCI {len(hci_stream)}B -> {verdict}")

    if args.json_path:
        with open(args.json_path, "w", encoding="utf-8") as handle:
            json.dump({
                "fridaDataEvents": total,
                "hciRfcommFrames": len(hci_frames),
                "matched": len(matches),
                "txMatched": len(tx_matches),
                "unmatched": len(unmatched),
                "offsetSpreadSeconds": spread,
                "streamComparison": stream_results,
                "matches": matches,
                "unmatchedEvents": unmatched,
            }, handle, ensure_ascii=False, indent=2)

    return 0 if tx_matches else 1


if __name__ == "__main__":
    sys.exit(main())

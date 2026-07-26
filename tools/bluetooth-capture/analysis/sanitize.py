"""Turns a capture into sanitized device-capture fixtures.

Two jobs, in this order:

1. Reassemble the RFCOMM byte stream into OPOv1 frames and inventory which
   commands actually appeared, so a fixture claims only what was observed.

2. Refuse to emit anything carrying personal data. Some OPPO frames embed
   human-readable device names: the multi-device connection list returns the
   phone's and laptop's Bluetooth names, which on a real handset contain the
   owner's name. Those frames are dropped, never redacted in place, because a
   partially-scrubbed frame still looks like valid evidence.

Output goes to the session's sanitized/ directory. Copying into the repository
stays a manual step with human review, per the capture plan.
"""

import argparse
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from oppo_frames import decode_stream            # noqa: E402

DEFAULT_TSHARK = r"C:\Program Files\Wireshark\tshark.exe"

# Printable ASCII runs and any multi-byte UTF-8 both indicate embedded names.
ASCII_RUN = re.compile(rb"[ -~]{6,}")

COMMAND_NAMES = {
    0x0100: "capability",
    0x0200: "notification-capability",
    0x0201: "notification-subscribe-single",
    0x0204: "notification-event",
    0x0205: "notification-subscribe-batch",
    0x0106: "battery",
    0x010C: "anc-query",
    0x010F: "eq-query",
    0x0404: "anc-set",
    0x0406: "eq-set",
    0x010D: "status-batch",
    0x0500: "device-info",
    0x0501: "device-list",
}


def command_name(cmd):
    base = cmd & 0x7FFF
    name = COMMAND_NAMES.get(base, f"unknown-{base:04X}")
    return f"{name}{'-response' if cmd & 0x8000 else ''}"


def looks_personal(payload):
    """True when a payload embeds human-readable text."""
    if ASCII_RUN.search(payload):
        return True
    try:
        text = payload.decode("utf-8")
    except UnicodeDecodeError:
        return False
    return any(ord(ch) > 0x7F for ch in text)


def extract_streams(tshark, capture, handles):
    handle_filter = " || ".join(f"bthci_acl.chandle == {h}" for h in handles)
    result = subprocess.run(
        [tshark, "-r", capture, "-Y", f"btrfcomm && data && ({handle_filter})",
         "-T", "fields", "-e", "hci_h4.direction", "-e", "data.data"],
        capture_output=True, text=True, check=True,
    )
    streams = {"tx": bytearray(), "rx": bytearray()}
    for line in result.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) < 2 or not parts[1]:
            continue
        token = parts[0].split(",")[0].strip()
        direction = "tx" if int(token, 16) == 0 else "rx"
        for chunk in parts[1].split(","):
            streams[direction] += bytes.fromhex(chunk.replace(":", ""))
    return streams


def build_inventory(streams):
    inventory = []
    for direction, stream in streams.items():
        frames, undecodable, remainder = decode_stream(bytes(stream))
        for frame in frames:
            inventory.append({
                "direction": direction,
                "cmd": frame.cmd,
                "name": command_name(frame.cmd),
                "seq": frame.seq,
                "payloadLength": len(frame.payload),
                "declaredLength": frame.declared_payload_length,
                "consistent": frame.consistent,
                "personal": looks_personal(frame.payload),
                "raw": frame.raw.hex(),
            })
        if undecodable:
            print(f"  {direction}: {len(undecodable)} 帧无法解码内层包", file=sys.stderr)
        if remainder:
            print(f"  {direction}: 尾部 {len(remainder)} 字节不足一帧（正常，捕获窗口边界）")
    return inventory


def write_fixture(path, entries, model, firmware, scenario):
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(f"# device-capture fixture: {model} / {firmware} / {scenario}\n")
        handle.write("# 真机抓包，非 official-source 静态推导。含个人信息的帧已整帧剔除。\n")
        for entry in entries:
            handle.write(f"\n# {entry['direction'].upper()} {entry['name']} "
                         f"cmd=0x{entry['cmd']:04X} seq={entry['seq']} "
                         f"payload={entry['payloadLength']}B\n")
            raw = entry["raw"]
            handle.write(" ".join(raw[i:i + 2].upper() for i in range(0, len(raw), 2)) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session", required=True)
    parser.add_argument("--handles", default="6")
    parser.add_argument("--tshark", default=DEFAULT_TSHARK)
    parser.add_argument("--model", required=True)
    parser.add_argument("--firmware", required=True)
    parser.add_argument("--scenario", required=True)
    args = parser.parse_args()

    raw_dir = os.path.join(args.session, "raw")
    captures = [f for f in os.listdir(raw_dir) if f.startswith("btsnoop") and f.endswith(".log")]
    if not captures:
        raise SystemExit(f"{raw_dir} 下没有 btsnoop 捕获")
    capture = os.path.join(raw_dir, captures[0])
    handles = [int(h) for h in args.handles.split(",") if h.strip()]

    streams = extract_streams(args.tshark, capture, handles)
    print(f"RFCOMM 流：tx {len(streams['tx'])}B / rx {len(streams['rx'])}B")
    inventory = build_inventory(streams)

    counts = {}
    for entry in inventory:
        counts[entry["name"]] = counts.get(entry["name"], 0) + 1
    print("\n命令清单：")
    for name, count in sorted(counts.items()):
        print(f"  {name:34s} {count}")

    personal = [e for e in inventory if e["personal"]]
    inconsistent = [e for e in inventory if not e["consistent"]]
    if personal:
        print(f"\n剔除含个人信息的帧：{len(personal)} 条 "
              f"({', '.join(sorted({e['name'] for e in personal}))})")
    if inconsistent:
        print(f"声明长度与实际不符的帧：{len(inconsistent)} 条（同样不进入 fixture）")

    keep = [e for e in inventory if not e["personal"] and e["consistent"]]

    sanitized_dir = os.path.join(args.session, "sanitized")
    os.makedirs(sanitized_dir, exist_ok=True)
    stem = f"{args.model}-{args.firmware}-{args.scenario}".replace(" ", "").lower()
    hex_path = os.path.join(sanitized_dir, f"{stem}.hex")
    json_path = os.path.join(sanitized_dir, f"{stem}.inventory.json")

    write_fixture(hex_path, keep, args.model, args.firmware, args.scenario)
    with open(json_path, "w", encoding="utf-8") as handle:
        json.dump({"kept": keep, "droppedPersonal": len(personal),
                   "droppedInconsistent": len(inconsistent)},
                  handle, ensure_ascii=False, indent=2)

    print(f"\n已写出 {len(keep)} 帧 -> {hex_path}")
    print("复制进仓库前必须人工复核，见抓包计划 12 节。")
    return 0


if __name__ == "__main__":
    sys.exit(main())

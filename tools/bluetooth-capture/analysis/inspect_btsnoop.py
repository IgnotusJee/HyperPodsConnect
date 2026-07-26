"""Structural validation and connection-handle mapping for btsnoop captures.

Runs before any TShark analysis. Two jobs:

1. Prove the record stream is intact (magic, datalink, per-record framing,
   monotonic timestamps, no truncation). A torn file read while the stack was
   still writing desynchronises every downstream tool silently.

2. Build the handle -> peer map from connection-completion events, then report
   ACL traffic on handles that were never opened. On HyperOS the stack injects
   vendor trace records as well-formed H4 ACL on a fixed high handle (0x0EDC
   observed on Xiaomi 13 Pro / Android 16). Those records pass every structural
   check and TShark happily dissects them as L2CAP, producing invented CIDs and
   garbled device names. Excluding unmapped handles is the only reliable filter.

Standard library only, so it runs under any Python the capture host has.
"""

import argparse
import json
import struct
import sys
from collections import Counter, OrderedDict

BTSNOOP_MAGIC = b"btsnoop\x00"
DATALINK_H4 = 1002
RECORD_HEADER = struct.Struct(">IIIIq")

H4_CMD, H4_ACL, H4_SCO, H4_EVT, H4_ISO = 0x01, 0x02, 0x03, 0x04, 0x05
H4_NAMES = {H4_CMD: "CMD", H4_ACL: "ACL", H4_SCO: "SCO", H4_EVT: "EVT", H4_ISO: "ISO"}

EVT_CONNECTION_COMPLETE = 0x03
EVT_DISCONNECTION_COMPLETE = 0x05
EVT_SYNC_CONNECTION_COMPLETE = 0x2C
EVT_LE_META = 0x3E
LE_CONNECTION_COMPLETE = 0x01
LE_ENHANCED_CONNECTION_COMPLETE = 0x0A

# btsnoop timestamps count microseconds from year 0; this is the offset of the
# Unix epoch in that scale.
EPOCH_OFFSET_US = 0x00DCDDB30F2F8000


def format_addr(raw):
    """HCI carries BD_ADDR little-endian; render it in display order."""
    return ":".join(f"{b:02X}" for b in reversed(raw))


def read_records(data):
    if data[:8] != BTSNOOP_MAGIC:
        raise ValueError("not a btsnoop file: bad magic")
    version, datalink = struct.unpack(">II", data[8:16])
    if datalink != DATALINK_H4:
        raise ValueError(f"unsupported datalink {datalink}, expected {DATALINK_H4} (H4)")

    offset = 16
    index = 0
    while offset + RECORD_HEADER.size <= len(data):
        orig_len, incl_len, flags, drops, timestamp = RECORD_HEADER.unpack_from(data, offset)
        start = offset + RECORD_HEADER.size
        end = start + incl_len
        if end > len(data):
            yield ("tail-truncated", index, offset, None, None, None, None)
            return
        yield ("record", index, offset, orig_len, incl_len, flags, (timestamp, data[start:end]))
        offset = end
        index += 1


def inspect(path):
    with open(path, "rb") as handle:
        data = handle.read()

    version, datalink = struct.unpack(">II", data[8:16]) if len(data) >= 16 else (None, None)

    stats = {
        "file": path,
        "sizeBytes": len(data),
        "version": version,
        "datalink": datalink,
        "records": 0,
        "truncatedRecords": 0,
        "timestampRegressions": 0,
        "tailTruncated": False,
        "h4Types": Counter(),
        "aclRecords": 0,
        "aclLengthMismatches": 0,
    }

    handle_map = OrderedDict()      # handle -> {addr, transport, openedAtRecord}
    closed_handles = []
    acl_handles = Counter()
    first_ts = None
    last_ts = None
    prev_ts = None

    for kind, index, offset, orig_len, incl_len, flags, payload_bundle in read_records(data):
        if kind == "tail-truncated":
            stats["tailTruncated"] = True
            break

        timestamp, payload = payload_bundle
        stats["records"] += 1
        if orig_len != incl_len:
            stats["truncatedRecords"] += 1
        if prev_ts is not None and timestamp < prev_ts:
            stats["timestampRegressions"] += 1
        prev_ts = timestamp
        if first_ts is None:
            first_ts = timestamp
        last_ts = timestamp

        if not incl_len:
            continue
        h4_type = payload[0]
        stats["h4Types"][h4_type] += 1

        if h4_type == H4_ACL and incl_len >= 5:
            stats["aclRecords"] += 1
            handle_flags, data_total = struct.unpack_from("<HH", payload, 1)
            conn_handle = handle_flags & 0x0FFF
            acl_handles[conn_handle] += 1
            if 5 + data_total != incl_len:
                stats["aclLengthMismatches"] += 1

        elif h4_type == H4_EVT and incl_len >= 3:
            code = payload[1]
            body = payload[3:]
            if code == EVT_CONNECTION_COMPLETE and len(body) >= 9:
                status = body[0]
                conn_handle = struct.unpack_from("<H", body, 1)[0] & 0x0FFF
                if status == 0:
                    handle_map[conn_handle] = {
                        "address": format_addr(body[3:9]),
                        "transport": "BR/EDR",
                        "openedAtRecord": index,
                    }
            elif code == EVT_SYNC_CONNECTION_COMPLETE and len(body) >= 9:
                status = body[0]
                conn_handle = struct.unpack_from("<H", body, 1)[0] & 0x0FFF
                if status == 0:
                    handle_map[conn_handle] = {
                        "address": format_addr(body[3:9]),
                        "transport": "SCO",
                        "openedAtRecord": index,
                    }
            elif code == EVT_DISCONNECTION_COMPLETE and len(body) >= 3:
                status = body[0]
                conn_handle = struct.unpack_from("<H", body, 1)[0] & 0x0FFF
                if status == 0:
                    closed_handles.append(conn_handle)
            elif code == EVT_LE_META and len(body) >= 1:
                subevent = body[0]
                if subevent in (LE_CONNECTION_COMPLETE, LE_ENHANCED_CONNECTION_COMPLETE) and len(body) >= 11:
                    status = body[1]
                    conn_handle = struct.unpack_from("<H", body, 2)[0] & 0x0FFF
                    if status == 0:
                        handle_map[conn_handle] = {
                            "address": format_addr(body[5:11]),
                            "transport": "LE",
                            "openedAtRecord": index,
                        }

    unmapped = {h: count for h, count in acl_handles.items() if h not in handle_map}

    result = {
        "file": stats["file"],
        "sizeBytes": stats["sizeBytes"],
        "version": stats["version"],
        "datalink": stats["datalink"],
        "records": stats["records"],
        "truncatedRecords": stats["truncatedRecords"],
        "timestampRegressions": stats["timestampRegressions"],
        "tailTruncated": stats["tailTruncated"],
        "aclLengthMismatches": stats["aclLengthMismatches"],
        "h4Types": {H4_NAMES.get(t, f"0x{t:02x}"): n for t, n in sorted(stats["h4Types"].items())},
        "firstTimestampUnixUs": (first_ts - EPOCH_OFFSET_US) if first_ts else None,
        "lastTimestampUnixUs": (last_ts - EPOCH_OFFSET_US) if last_ts else None,
        "connections": [
            {"handle": h, **info, "closed": h in closed_handles}
            for h, info in handle_map.items()
        ],
        "aclHandles": {f"0x{h:04X}": n for h, n in acl_handles.most_common()},
        "unmappedAclHandles": {f"0x{h:04X}": n for h, n in sorted(unmapped.items())},
    }

    problems = []
    if stats["tailTruncated"]:
        problems.append("文件尾部记录不完整：很可能在协议栈仍在写入时复制，必须重新提取")
    if stats["truncatedRecords"]:
        problems.append(f"{stats['truncatedRecords']} 条记录 includedLength 小于 originalLength，载荷被截断")
    if stats["timestampRegressions"]:
        problems.append(f"{stats['timestampRegressions']} 处时间戳回退，记录流可能被拼接")
    if stats["aclLengthMismatches"]:
        problems.append(f"{stats['aclLengthMismatches']} 条 ACL 记录长度字段与记录长度不符")
    if unmapped:
        rendered = ", ".join(f"{h}({n} 帧)" for h, n in result["unmappedAclHandles"].items())
        problems.append(
            f"存在无连接建立事件的 ACL 句柄：{rendered}。"
            "这类记录在 HyperOS 上是厂商 trace 伪装成 ACL，会被 TShark 误解析成 L2CAP，必须排除"
        )
    if not result["connections"]:
        problems.append("捕获窗口内没有任何连接建立事件，无法产出 target-only 输出")

    result["problems"] = problems
    result["ok"] = not any(
        p for p in problems if "厂商 trace" not in p and "没有任何连接建立事件" not in p
    )
    return result


def build_target_filter(result, address=None):
    """Display filter keeping only handles proven to belong to a real connection."""
    handles = [c["handle"] for c in result["connections"]
               if address is None or c["address"].upper() == address.upper()]
    if not handles:
        return None
    acl = " || ".join(f"bthci_acl.chandle == {h}" for h in handles)
    return f"({acl})"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path")
    parser.add_argument("--json", dest="json_path", help="写出 JSON 结果")
    parser.add_argument("--address", help="只为该 BD_ADDR 生成 target filter")
    parser.add_argument("--print-filter", action="store_true", help="只输出 TShark 显示过滤器")
    args = parser.parse_args()

    result = inspect(args.path)

    if args.print_filter:
        filt = build_target_filter(result, args.address)
        if not filt:
            print("", end="")
            return 2
        print(filt)
        return 0

    if args.json_path:
        with open(args.json_path, "w", encoding="utf-8") as handle:
            json.dump(result, handle, ensure_ascii=False, indent=2)

    print(f"file        : {result['file']}")
    print(f"size        : {result['sizeBytes']} bytes")
    print(f"datalink    : {result['datalink']} (1002 = H4)")
    print(f"records     : {result['records']}  truncated={result['truncatedRecords']}  "
          f"tsRegressions={result['timestampRegressions']}  tailTruncated={result['tailTruncated']}")
    print(f"h4 types    : {result['h4Types']}")
    print(f"acl handles : {result['aclHandles']}")
    print("connections :")
    if result["connections"]:
        for conn in result["connections"]:
            print(f"  handle 0x{conn['handle']:04X}  {conn['transport']:6s}  {conn['address']}"
                  f"  closed={conn['closed']}")
    else:
        print("  (none)")
    if result["unmappedAclHandles"]:
        print(f"unmapped    : {result['unmappedAclHandles']}")
    if result["problems"]:
        print("problems    :")
        for problem in result["problems"]:
            print(f"  - {problem}")
    else:
        print("problems    : none")

    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())

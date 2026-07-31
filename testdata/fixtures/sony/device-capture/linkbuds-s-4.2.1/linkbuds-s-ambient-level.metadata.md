# LinkBuds S 4.2.1 BLE GATT ambient-level capture

- Capture date: 2026-07-31
- Headset: Sony LinkBuds S
- Firmware reported by the headset: 4.2.1
- Phone: Xiaomi 13 Pro
- OS: Android 16 / API 36
- Transport: BLE GATT, Sony Tandem v2 HPC, MDR table 1
- Official app: Sony Sound Connect 13.2.0
- Parameter: NCASM `0x17`, support-function code `0x17FF`
- Initial and restored state: ambient sound, normal ambient mode, level 10
- Boundary sequence: `10 -> 1 -> 20 -> 10`

Observed payload layout:

| Byte after parameter id | Meaning | Observed values |
| --- | --- | --- |
| 0 | `ValueChangeStatus` | `0x00` while dragging, `0x01` when committed |
| 1 | NC/ASM enabled | `0x01` |
| 2 | NC/ASM mode | `0x01` (ambient sound) |
| 3 | ambient sub-mode | `0x00` (normal; `0x01` is voice mode) |
| 4 | ambient level | `0x01` through `0x14` (1 through 20) |

Validation result:

- The official UI exposed an inclusive level range of 1 through 20.
- While the slider moved, the app sent representative intermediate
  `SET_PARAM 0x68/0x17` frames with `ValueChangeStatus=UNDER_CHANGING (0x00)`.
- At levels 1, 20 and restored 10, the app sent a final frame with
  `ValueChangeStatus=CHANGED (0x01)`.
- Each final SET received a Tandem ACK and a matching
  `NTFY_PARAM 0x69/0x17`.
- The final device notification reported ambient mode, normal sub-mode and
  level 10.
- The HCI capture contained 8,931 structurally valid records with no truncated
  record, tail truncation or timestamp regression. HyperOS pseudo-ACL trace
  handle `0x0EDC` was excluded.

Privacy handling:

- Bluetooth addresses, phone serials, account data, device-unique identifiers,
  LE Audio group/member identifiers and unrelated payloads are not retained.
- The fixture keeps only complete Tandem frames needed to prove field
  semantics, boundaries, acknowledgements and restoration.
- Raw btsnoop and other capture artifacts remain outside the Git workspace.

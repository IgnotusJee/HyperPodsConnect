# LinkBuds S 4.2.1 BLE GATT ambient NORMAL/VOICE capture

- Capture date: 2026-07-31
- Headset: Sony LinkBuds S
- Firmware reported by the headset: 4.2.1
- Phone: Xiaomi 13 Pro
- OS: Android 16 / API 36
- Transport: BLE GATT, Sony Tandem v2 HPC, MDR table 1
- Official app: Sony Sound Connect 13.2.0
- Parameter: NCASM `0x17`, support-function code `0x17FF`
- Initial and restored state: ambient sound, NORMAL sub-mode, level 20
- Sequence: three `NORMAL -> VOICE -> NORMAL` cycles

Observed payload layout:

| Byte after parameter id | Meaning | Observed values |
| --- | --- | --- |
| 0 | `ValueChangeStatus` | `0x01` (`CHANGED`) |
| 1 | NC/ASM enabled | `0x01` |
| 2 | NC/ASM mode | `0x01` (ambient sound) |
| 3 | ambient sub-mode | `0x00` NORMAL, `0x01` VOICE |
| 4 | ambient level | `0x14` (20), preserved by every write |

Validation result:

- Each of the six `SET_PARAM 0x68/0x17` writes received an independent Tandem
  ACK and a matching `NTFY_PARAM 0x69/0x17`.
- Only the ambient sub-mode byte changed; enabled, ambient selection and level
  remained unchanged.
- The final notification reported ambient sound, NORMAL sub-mode and level 20.
- The HCI capture contained 5,793 structurally valid records with no truncated
  record, tail truncation or timestamp regression. HyperOS pseudo-ACL trace
  handle `0x0EDC` was excluded.

Privacy handling:

- Bluetooth addresses, phone serials, account data, device-unique identifiers,
  LE Audio group/member identifiers and unrelated payloads are not retained.
- The fixture keeps only complete Tandem frames required to prove the reversible
  NORMAL/VOICE transition, acknowledgements and restoration.
- Raw btsnoop and other capture artifacts remain under the ignored project-local
  `.codex_tmp` capture directory.

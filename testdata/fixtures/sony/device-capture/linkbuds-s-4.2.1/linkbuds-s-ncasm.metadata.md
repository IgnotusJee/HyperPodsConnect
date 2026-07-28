# LinkBuds S 4.2.1 BLE GATT NCASM capture

- Capture date: 2026-07-27
- Headset: Sony LinkBuds S
- Firmware reported by the headset: 4.2.1
- Phone: Xiaomi 13 Pro
- OS: Android 16 / API 36
- Transport: BLE GATT, Sony Tandem v2 HPC, MDR table 1
- Official app: Sony Sound Connect 13.0.8
- Parameter: NCASM `0x17`, support-function code `0x17FF`
- Baseline and restored state: off
- Repetition: noise cancellation and ambient sound were each observed three
  times; off was observed after both source modes
- Cold-start evidence: `GET_PARAM 0x66/0x17` returned
  `RET_PARAM 0x67/0x17`
- Write evidence: each `SET_PARAM 0x68/0x17` received a separate Tandem ACK
  and `NTFY_PARAM 0x69/0x17`
- Cross-layer correlation: the action session matched all 64 Frida GATT events
  to HCI frames byte-for-byte (32 TX and 32 RX, 0 unmatched, 0.021 s offset
  spread); the cold-start session matched all 234 events (117 TX and 117 RX,
  0 unmatched, 0.034 s spread)

Project implementation validation (2026-07-28):

- App version: 2.0.7 debug build with Phase 10 Sony control enabled
- Initial state: explicit `GET_PARAM 0x66/0x17` returned off
- UI gate: only off, noise cancellation and ambient sound were rendered; the
  unverified smart/light/medium/deep controls were absent
- Noise-cancellation transition: `SET_PARAM 0x68/0x17` received a Tandem ACK;
  after the bounded notification grace period, explicit GET returned noise
  cancellation
- Restore transition: `SET_PARAM 0x68/0x17` received a Tandem ACK; explicit GET
  returned off
- Device behavior difference: unlike the official app session, the project
  session received no `NTFY_PARAM 0x69/0x17`; ACK plus mandatory GET readback
  therefore forms the confirmation path
- HCI evidence: both SET/GET/RET groups were present byte-for-byte in a
  target-only, session-windowed capture; the capture had no truncated record,
  no tail truncation and no timestamp regression
- The final confirmed and physical headset state was restored to off

Observed payload states:

| State | `enabled` | `ambient` | preserved level |
| --- | ---: | ---: | ---: |
| Off | `0x00` | previous selector on SET; normalized to `0x00` on NTFY | 10 |
| Noise cancellation | `0x01` | `0x00` | 10 |
| Ambient sound | `0x01` | `0x01` | 10 |

Privacy handling:

- Bluetooth addresses, phone serials, account data, device-unique identifiers,
  LE Audio group/member identifiers and unrelated payloads are not retained.
- Only complete, checksum-valid Tandem frames required to prove the NCASM
  layout and reversible state transitions are included.
- Raw Frida, btsnoop, screenshots and UI hierarchy remain outside the Git
  workspace.

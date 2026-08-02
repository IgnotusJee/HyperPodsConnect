# WH-1000XM4 2.5.1 Classic SPP reversible controls

- Capture date: 2026-08-02
- Headset: Sony WH-1000XM4
- Firmware reported by the headset: 2.5.1
- Phone: Xiaomi 13 Pro
- OS: Android 16
- Transport: secure Classic SPP, Sony Tandem v1 / MDR table 1
- App under test: HyperPodsConnect debug build
- Baseline and restored state: noise cancellation, ambient preference
  `VOICE/20`, EQ `Custom 2`
- Result: every SET received a Tandem ACK and device NTFY; the mandatory
  follow-up GET returned the same state
- Connection result: A2DP remained active with LDAC and the SPP session stayed
  connected throughout the reversible sequence

Validated transitions:

- ambient `VOICE -> NORMAL -> VOICE`;
- ambient level `20 -> 8 -> 20`;
- EQ `Custom 2 -> Bass Boost -> Custom 2`;
- ambient sound -> noise cancellation restoration;
- noise cancellation -> global effect `OFF` -> noise cancellation.

Evidence boundary:

- The captured reversible sequence first promoted the exact
  model/firmware/transport tuple to `CONTROLLED`.
- Sound Connect 13.2.1 static analysis establishes V1 global `OFF` as effect
  byte `0x00`, while preserving the current NC/ASM capability fields.
- The project implementation's exact OFF SET received ACK/NTFY and its GET/RET
  readback confirmed global `OFF`; the following NC SET/GET restored baseline.
- A subsequent automated 20/20 connection/control stability gate repeated
  reconnect, ambient SET/GET and noise-cancellation restore SET/GET with zero
  protocol reject or timeout, promoting the tuple to `STABLE`.

Privacy handling:

- Bluetooth addresses, phone serials, accounts and device-unique capability
  identifiers are not retained.
- Only complete checksum-valid Tandem frames needed for control/readback proof
  are included.

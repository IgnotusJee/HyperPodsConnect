# WH-1000XM4 2.5.1 Classic SPP custom EQ

- Capture date: 2026-08-02
- Headset: Sony WH-1000XM4
- Firmware reported by the headset: 2.5.1
- Phone: Xiaomi 13 Pro
- OS: Android 16
- Transport: secure Classic SPP, Sony Tandem v1 / MDR table 1
- App under test: HyperPodsConnect debug build
- Active slot: `Custom 2`
- Baseline and restored encoded curve: `0A 13 11 13 12 11`
- Test curve: `0B 13 11 13 12 11` (first displayed gain `0 -> +1`)

Result:

- HyperPodsConnect sent the official V1 whole-curve form
  `58 01 FF 06 <six encoded bands>`;
- both SET operations received a Tandem ACK;
- this firmware did not emit an EQ parameter notification for either curve edit;
- the mandatory `56 01` query returned the exact active `Custom 2` slot and complete curve;
- the second operation restored the original curve, and the SPP/A2DP connection remained active.

Privacy handling:

- Bluetooth addresses, phone serials, accounts and device-unique capability identifiers are omitted;
- only checksum-valid Tandem frames required to prove write, readback and restoration are retained.

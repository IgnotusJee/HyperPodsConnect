# LinkBuds S 4.2.1 BLE GATT custom EQ

- Capture date: 2026-08-03
- Headset: Sony LinkBuds S
- Firmware reported by the headset: 4.2.1
- Phone: Xiaomi 13 Pro
- OS: Android 16
- Transport: BLE GATT, Sony Tandem v2 / MDR table 1
- App under test: HyperPodsConnect debug build
- Active slot: `Custom 1` (`0xA1`)
- Baseline and restored encoded curve: `08 11 0A 0A 0B 0C`
- Test curve: `08 10 0A 0A 0B 0C` (400 Hz displayed gain `+7 -> +6`)

The same live session first returned the table-set 2 capability payload
`51 00 06 15 0C 00 00 10 00 11 00 12 00 13 00 14 00 15 00 16 00 17 00 A0 00 A1 00 A2 00`:
six bands, 21 levels and 12 presets, including writable custom slots `A0/A1/A2`.

Result:

- HyperPodsConnect sent the official V2 whole-curve form
  `58 00 A1 06 <six encoded bands>`;
- both SET operations received a Tandem ACK;
- this firmware did not emit an EQ parameter notification for either curve edit;
- the mandatory `56 00` query returned the exact active `Custom 1` slot and complete curve;
- the second operation restored the original curve, and the session remained `Ready/STABLE`.

Privacy handling:

- Bluetooth addresses, phone serials, accounts and device-unique identifiers are omitted;
- only checksum-valid Tandem frames required to prove write, readback and restoration are retained.

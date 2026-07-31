# LinkBuds S 4.2.1 EQ preset capture

- Capture date: 2026-07-31
- Headset: Sony LinkBuds S
- Firmware: 4.2.1
- Phone: Xiaomi 13 Pro
- OS: Android 16 / API 36
- Official app: Sony Sound Connect 13.2.0
- Transport: Sony Tandem v2 HPC over BLE GATT, MDR table 1
- Baseline preset: `Bass Boost` / `低音增强` (`0x16`)
- Adjacent preset: `Speech` / `演说` (`0x17`)
- Action sequence: `0x16 -> 0x17 -> 0x16`, repeated three times

The project's LSPosed module was disabled and the Bluetooth scope was restarted
before capture, so the official app was the only Sony control-session owner.
The fresh action window contains 88 target frames. The raw HCI file was frozen,
pulled and SHA-256 verified; it has zero truncated records and no truncated tail.
The raw material remains in the Git-ignored project temporary directory.

Wire conclusions:

- Initial read uses EQEBB `GET_PARAM 0x56` with inquired type `0x00` and returns
  `RET_PARAM 0x57` with layout
  `<type=0x00> <preset> <count=0x06> <six encoded band values>`.
- Preset writes use `SET_PARAM 0x58 00 <preset> 00`.
- Each of the six writes received an independent Tandem ACK and an EQEBB
  `NTFY_PARAM 0x59` reporting the exact selected preset and its six values.
- `0x16` consistently reported `06 11 0A 0A 0A 0A 0A` after the preset byte.
- `0x17` consistently reported `06 00 0E 0D 0B 0C 00` after the preset byte.
- The official app also issued `GET_EXTENDED_INFO 0x5A` after each change. That
  metadata query is not required to select a preset and is not exposed here.
- The final notification reported `0x16`, matching the baseline restored in the
  official UI.

This was an HCI-only action capture because host-side Frida installation was not
authorized. The command/ACK/notification grouping is nevertheless unambiguous:
the six SET frames are the only outbound EQEBB writes in the action window and
each is immediately followed by its ACK and matching notification.

Privacy handling:

- Bluetooth addresses, phone serial, account data and LE group identifiers are
  omitted.
- Only complete, checksum-valid Tandem frames relevant to EQ preset control are
  retained in the sanitized fixture.

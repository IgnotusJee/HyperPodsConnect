# LinkBuds S 4.2.1 complete EQ preset capture

- Capture date: 2026-07-31
- Headset: Sony LinkBuds S
- Firmware: 4.2.1
- Phone: Xiaomi 13 Pro
- OS: Android 16 / API 36
- Official app: Sony Sound Connect 13.2.0
- Transport: Sony Tandem v2 HPC over BLE GATT, MDR table 1
- Baseline/final preset: `Bass Boost` / `低音增强` (`0x16`)
- Selection method: UI-hierarchy bounded automated taps on the official EQ
  carousel's next arrow; the edit button was never used

The project's LSPosed module was disabled and the Bluetooth scope restarted
before capture, leaving Sony Sound Connect as the only Sony control-session
owner. Starting at Bass Boost, the automation selected every adjacent carousel
entry exactly once and wrapped back to the baseline:

`Speech -> Manual -> Custom 1 -> Custom 2 -> Off -> Bright -> Excited ->`
`Mellow -> Relaxed -> Vocal -> Treble Boost -> Bass Boost`.

The labels above are the official English resources from the installed Sony
Sound Connect APK. The Chinese UI displayed `演说 / 手动 / 自定义 1 / 自定义 2 /
关闭 / 欢快 / 激昂 / 醇美 / 放松 / 原声 / 高音增强 / 低音增强`.

## Verified preset map

| Vendor ID | Official label | Six encoded band values |
| --- | --- | --- |
| `0x00` | Off | `0A 0A 0A 0A 0A 0A` |
| `0x10` | Bright | `09 0A 0F 11 11 13` |
| `0x11` | Excited | `12 09 0B 0A 0D 0F` |
| `0x12` | Mellow | `07 09 08 07 06 04` |
| `0x13` | Relaxed | `01 07 09 07 05 02` |
| `0x14` | Vocal | `0A 10 0E 0C 0D 09` |
| `0x15` | Treble Boost | `0A 0A 0A 0C 10 14` |
| `0x16` | Bass Boost | `11 0A 0A 0A 0A 0A` |
| `0x17` | Speech | `00 0E 0D 0B 0C 00` |
| `0xA0` | Manual | `00 0A 0A 0A 06 02` |
| `0xA1` | Custom 1 | `08 11 0A 0A 0B 0C` |
| `0xA2` | Custom 2 | `01 01 14 0A 0B 0C` |

Every selection emitted the exact write `SET_PARAM 0x58 00 <preset> 00`,
received an independent Tandem ACK, and was followed by a matching
`NTFY_PARAM 0x59 00 <preset> 06 <six values>` plus its ACK. During a transient
two-earbud LE reconnect after selecting Vocal, the official app's fresh
`GET_PARAM 0x56` / `RET_PARAM 0x57` independently confirmed `0x14` before the
automation continued. The final notification confirmed `0x16`, matching the
restored official UI.

The frozen raw capture was pulled with a matching device/host SHA-256. Its final
inspection reported 8,601 records, zero truncated records and no truncated
tail. Three timestamp regressions occurred across the cumulative raw file while
the two LE members reconnected; transaction ordering remains unambiguous from
frame order, proven handles and the exact SET/ACK/NTFY groups. HyperOS pseudo
ACL handle `0x0EDC` was excluded. The target-only/window output contains 1,661
frames across the proven LinkBuds handles.

Privacy handling:

- Bluetooth addresses, phone serial, account data and LE group identifiers are
  omitted.
- Only complete, checksum-valid Tandem frames relevant to EQ selection and the
  reconnect readback are retained in the fixture.
- Selecting Manual or Custom 1/2 is verified; editing any band's value remains
  outside scope and is not exposed by the project.

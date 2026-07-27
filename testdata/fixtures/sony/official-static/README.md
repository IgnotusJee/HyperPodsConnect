# Sony Phase 8 static vectors

These are synthetic golden vectors calculated from the framing and command
definitions in `reference/reverse-engineering-reference`. They are **not**
Bluetooth captures and must not be cited as dynamic device evidence.

Evidence scope:

- Tandem SOF/EOF/escape/checksum and big-endian payload length
- MDR table-1 routing
- protocol-info GET/RET
- v2 POWER single-battery GET/RET

Missing until a real-device gate is run:

- SDP availability on the selected paired headset
- ACK timing and reconnect stability
- model/firmware/battery parity with Sony Sound Connect

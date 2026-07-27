# WH-1000XM4 2.5.1 read-only capture

- Capture date: 2026-07-27
- Headset: Sony WH-1000XM4
- Firmware reported by the headset: 2.5.1
- Topology: headband / single battery
- Phone: Xiaomi 13 Pro
- OS: Android 16
- Transport: secure Classic SPP
- SDP service: Sony v1 SPP UUID
- Protocol: Sony Tandem v1, MDR table 1
- Sony Sound Connect: 13.0.8, force-stopped during OppoPods tests
- Result: model, firmware, support-function and 100% non-charging battery
  reached `READ_ONLY`
- Stability gate: 20/20 complete connect/handshake/disconnect generations;
  zero failed snapshots, ACK/response timeouts, decode rejects or relevant
  crashes; Bluetooth process remained stable
- Official parity check after the gate: Sound Connect showed WH-1000XM4,
  firmware 2.5.1 and 100% battery; it was then force-stopped again

Privacy handling:

- Bluetooth addresses, phone serials and account data are not retained.
- The complete capability-info response is excluded because it contains a
  device-unique identifier.
- This fixture contains only complete, checksum-valid protocol/model/firmware/
  support/battery Tandem frames.

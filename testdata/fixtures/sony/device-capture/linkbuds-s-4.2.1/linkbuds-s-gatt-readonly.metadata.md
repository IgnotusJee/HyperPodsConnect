# LinkBuds S 4.2.1 BLE GATT read-only capture

- Capture date: 2026-07-27
- Headset: Sony LinkBuds S
- Firmware reported by the headset: 4.2.1
- Topology: TWS / left, right and charging case batteries
- Phone: Xiaomi 13 Pro
- OS: Android 16 / API 36
- Transport: BLE GATT over the bonded LE Audio coordinated set
- GATT service: Sony Tandem v2 HPC
- Protocol: Sony Tandem v2, MDR table 2
- Sony Sound Connect: force-stopped during OppoPods tests
- Baseline result: model, firmware, 40 support functions, left 87%, right
  100% and case 96% reached `READ_ONLY`
- Single-ear/in-case result: with the right earbud closed in its case, the
  Android LE Audio group kept the left member active; a new GATT session
  resolved the connected group lead, reached `READ_ONLY`, and reported left
  78%, right unavailable (0%) and case 96%
- Full in-case/disconnect result: after both earbuds entered the closed case,
  the ready generation failed with link loss, retained its last state as stale,
  and performed exactly three bounded BLE GATT retries at 1 s, 2 s and 4 s;
  the fourth generation stopped in `Failed` with no SPP fallback, unbounded
  loop or relevant process crash
- Recovery result: after the left member reconnected, selecting the already
  audio-connected row issued an idempotent control-session request; generation
  5 completed the full handshake and returned to `READ_ONLY` with left 71%,
  right unavailable (0%) and case 94%
- Second-phone verification: Xiaomi 17 Pro, HyperOS 3.0
  (OS3.0.315.0.WBLCNXM), Android 16 / API 36 and LSPosed API 102. After the
  Bluetooth scope restarted, both coordinated-set members reconnected
  automatically. A fresh BLE GATT generation completed the full handshake,
  reported model `LinkBuds S`, firmware `4.2.1`, 40 support functions, left
  80%, right 100% and case 93%, and reached `READ_ONLY` without SPP fallback
  or a relevant process crash.

Privacy handling:

- Bluetooth addresses, phone serials, account data and LE Audio group/member
  identifiers are not retained.
- The complete capability-info response is excluded because it contains a
  device-unique identifier.
- This fixture contains only complete, checksum-valid protocol/model/firmware/
  support/battery Tandem frames.

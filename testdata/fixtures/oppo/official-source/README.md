# OPPO official-source vectors

These sanitized vectors are derived from the official application packet layout,
command table, and parser behavior documented in
`docs/reverse-engineering/OPPO_OFFICIAL_APP_DEXDUMP_ANALYSIS.md`.

They are static test inputs, not Bluetooth captures and not proof that a specific
headset model or firmware supports a write operation. Real captures belong under
`testdata/fixtures/oppo/device-capture/` and must include the
metadata required by `PHASE0_OPPO_BASELINE.md`.

`custom-eq-response.hex` and `custom-eq-update.hex` encode the exact field order
implemented by HeyMelody 16.7.1 for GET `0x0122` / SET `0x0418`. They prove the
local parser and serializer match the official app's static contract; they are
deliberately not labeled as Enco Air5s captures or dynamic write evidence.

The separate Enco Air5s 163.163.102 lifecycle evidence is under
`../device-capture/enco-air5s-163.163.102/`; it does not change the provenance of
these official-source vectors.


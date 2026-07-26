# OPPO device-capture fixtures

This directory is reserved for sanitized, real-device captures. Do not copy raw
HCI snoops, real Bluetooth addresses, account data, tokens, or firmware binaries
into the repository.

Each accepted fixture should use a pair of files:

- `<model>-<firmware>-<scenario>.hex`: ordered TX/RX frames with addresses and
  unrelated traffic removed;
- `<model>-<firmware>-<scenario>.metadata.md`: model, firmware, transport,
  actual service UUID, phone model, Android/HyperOS version, capture time,
  scenario, expected decoded result, and sanitization notes.

Required scenarios are listed in
`docs/architecture/PHASE0_OPPO_BASELINE.md`. Until a fixture is backed by a real
capture, keep it in `app/src/test/resources/fixtures/oppo/official-source/` and
label it `official-source`; never copy or relabel a static vector here.


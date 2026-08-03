# OPPO Enco Air5s custom EQ lifecycle

- Capture date: 2026-08-03
- Host: Xiaomi 13 Pro, Android 16 / HyperOS
- Device: OPPO Enco Air5s
- Firmware: 163.163.102
- Transport: Classic SPP
- Capability: bit 34 set
- Address: omitted

The headset initially returned a successful `0x8122` response with zero custom slots. The test created
`CodexTmp`; the headset assigned ID 4 and returned six bands at 62, 250, 1000, 4000, 8000 and 16000 Hz
with range -6..+6 dB.

The first band was changed from 0 to +1 dB through the typed `SetEqualizerCurve` path. A forced `0x0122`
readback returned `[1, 0, 0, 0, 0, 0]` with `source=READ_BACK`. The same path restored the neutral curve
and read it back. Action 3 then deleted the temporary slot; after the official app's one-second refresh
delay, state was `ids=[]` and `curve=null`.

Finally, all selected LSPosed scope processes were restarted. The OPPO session returned to
`Ready/STABLE` and another typed GET returned `ids=[]`, `curve=null`. No temporary slot remains on the
headset.

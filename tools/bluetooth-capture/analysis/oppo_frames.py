"""OPOv1 link-frame reassembly and inner-packet decoding for captured streams.

Mirrors the framing the app module implements in OppoFrameStreamDecoder.kt so a
capture can be turned into the same frames the JVM parsers consume:

    0xAA | 7-bit varint encoded length | control/reserved(2) | inner packet

The encoded length counts the control bytes and the inner packet but excludes
the 0xAA and the varint itself. Inner packets are
cmd(2, LE) | seq(1) | payloadLength(2, LE) | payload.

This is analysis-side only; it never runs on device and never sends anything.
"""

FRAME_MARKER = 0xAA
MAX_VARINT_BYTES = 3
MIN_ENCODED_LENGTH = 2
DEFAULT_MAX_ENCODED_LENGTH = 16 * 1024


class Frame:
    __slots__ = ("raw", "control", "cmd", "seq", "payload", "declared_payload_length")

    def __init__(self, raw, control, cmd, seq, payload, declared_payload_length):
        self.raw = raw
        self.control = control
        self.cmd = cmd
        self.seq = seq
        self.payload = payload
        self.declared_payload_length = declared_payload_length

    @property
    def is_response(self):
        return bool(self.cmd & 0x8000)

    @property
    def request_cmd(self):
        return self.cmd & 0x7FFF

    @property
    def consistent(self):
        return self.declared_payload_length == len(self.payload)

    def __repr__(self):
        kind = "RET" if self.is_response else "CMD"
        return (f"<{kind} 0x{self.cmd:04X} seq={self.seq} "
                f"len={len(self.payload)} {self.payload.hex()}>")


def _read_varint(data, start):
    """Returns (value, size) or (None, None) when incomplete/invalid."""
    value = 0
    shift = 0
    for index in range(start, min(len(data), start + MAX_VARINT_BYTES)):
        current = data[index]
        value |= (current & 0x7F) << shift
        if not (current & 0x80):
            return value, index - start + 1
        shift += 7
    if len(data) - start <= MAX_VARINT_BYTES:
        return None, None          # incomplete
    return None, -1                # invalid


def split_frames(stream, max_encoded_length=DEFAULT_MAX_ENCODED_LENGTH):
    """Splits a byte stream into complete link frames.

    Resynchronises on the next marker when a length is malformed, and stops at
    the first incomplete frame, returning it as the remainder.
    """
    frames = []
    pending = bytes(stream)

    while pending:
        marker = pending.find(bytes([FRAME_MARKER]))
        if marker < 0:
            pending = b""
            break
        if marker > 0:
            pending = pending[marker:]

        encoded_length, varint_size = _read_varint(pending, 1)
        if encoded_length is None and varint_size is None:
            break                                   # need more bytes
        if encoded_length is None or not (MIN_ENCODED_LENGTH <= encoded_length <= max_encoded_length):
            pending = pending[1:]                   # bad length, resync
            continue

        total = 1 + varint_size + encoded_length
        if len(pending) < total:
            break
        frames.append(pending[:total])
        pending = pending[total:]

    return frames, pending


def decode_frame(raw):
    """Decodes one complete link frame into its inner packet, or None."""
    encoded_length, varint_size = _read_varint(raw, 1)
    if encoded_length is None:
        return None
    body = raw[1 + varint_size:]
    if len(body) < 2:
        return None
    control = body[:2]
    inner = body[2:]
    if len(inner) < 5:
        return None
    cmd = inner[0] | (inner[1] << 8)
    seq = inner[2]
    declared = inner[3] | (inner[4] << 8)
    payload = inner[5:]
    return Frame(raw, control, cmd, seq, payload, declared)


def decode_stream(stream):
    """Convenience: stream -> (frames, undecodable_raw_frames, remainder)."""
    raw_frames, remainder = split_frames(stream)
    decoded, undecodable = [], []
    for raw in raw_frames:
        frame = decode_frame(raw)
        if frame is None:
            undecodable.append(raw)
        else:
            decoded.append(frame)
    return decoded, undecodable, remainder

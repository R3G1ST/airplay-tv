#!/usr/bin/env python3
"""Generate ALAC bit-exact verification fixtures using macOS afconvert.

Produces, for each frame length (4096 and 352):

  cookie.bin          ALACSpecificConfig magic cookie (kuki chunk)
  frame_%04d.bin      one raw ALAC packet per file
  samples.txt         one line per frame: "<index> <sample count>"
  expected.pcm        raw little-endian interleaved 16-bit PCM decoded by afconvert

The script is intentionally self-contained (stdlib only). It exits non-zero
when the platform cannot produce fixtures; AlacVerify treats that as "skip".
"""

import math
import os
import random
import struct
import subprocess
import sys
import wave


def fail(message):
    print("SKIP: " + message, file=sys.stderr)
    raise SystemExit(42)


def make_wav(path):
    sr = 44100
    total = sr * 2
    rng = random.Random(0x5EED)
    frames = bytearray()
    for i in range(total):
        t = i / sr
        sweep = 0.35 * math.sin(2.0 * math.pi * (100.0 * t + (7900.0 / 4.0) * t * t))
        noise = 0.12 * rng.uniform(-1.0, 1.0)
        if t < 0.2:
            env = 0.0
        elif t < 1.6:
            env = 1.0
        else:
            env = max(0.0, 1.0 - (t - 1.6) / 0.4)
        left = int(max(-32768, min(32767, (sweep + noise) * env * 12000)))
        right = int(max(-32768, min(32767, (0.8 * sweep - 0.9 * noise) * env * 12000)))
        frames += struct.pack("<hh", left, right)
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(bytes(frames))


def run(cmd):
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if proc.returncode != 0:
        fail("%s failed: %s" % (" ".join(cmd), proc.stdout.strip()))
    return proc.stdout


def parse_caf(path):
    data = open(path, "rb").read()
    if data[:4] != b"caff":
        fail("not a CAF file: " + path)
    pos = 8
    chunks = []
    while pos + 12 <= len(data):
        kind = data[pos:pos + 4].decode("latin1")
        size = struct.unpack(">q", data[pos + 4:pos + 12])[0]
        body = pos + 12
        chunks.append((kind, body, size))
        if kind == "data":
            return data, chunks
        if size < 0:
            break
        pos = body + size
    fail("CAF data chunk not found")


def read_varint(buf, pos):
    value = 0
    while True:
        byte = buf[pos]
        pos += 1
        value = (value << 7) | (byte & 0x7F)
        if not (byte & 0x80):
            return value, pos


def bit_at(buf, pos):
    return (buf[pos >> 3] >> (7 - (pos & 7))) & 1


def read_bits(buf, pos, count):
    value = 0
    for _ in range(count):
        value = (value << 1) | bit_at(buf, pos)
        pos += 1
    return value


def declared_samples(frame, frame_length):
    """Return the sample count declared by the element header, if any."""
    tag = read_bits(frame, 0, 3)
    if tag not in (0, 1):  # SCE/LFE or CPE
        return frame_length
    header = read_bits(frame, 19, 4)
    if header >> 3:
        return read_bits(frame, 23, 32)
    return frame_length


def emit_fixture(wav, outdir, frame_length):
    os.makedirs(outdir, exist_ok=True)
    caf = os.path.join(outdir, "encoded.caf")
    ref = os.path.join(outdir, "reference.wav")
    spec = "alac#%d" % frame_length
    run(["afconvert", "-f", "caff", "-d", spec, "-c", "2", wav, caf])
    run(["afconvert", "-f", "WAVE", "-d", "LEI16", caf, ref])

    data, chunks = parse_caf(caf)
    by_kind = {kind: (body, size) for kind, body, size in chunks}

    if "kuki" not in by_kind or "pakt" not in by_kind or "data" not in by_kind:
        fail("CAF file is missing kuki/pakt/data chunks")

    kuki_body, kuki_size = by_kind["kuki"]
    cookie = data[kuki_body:kuki_body + kuki_size]
    config = struct.unpack(">IBBBBBBHIII", cookie[:24])
    cookie_frame_length = config[0]
    channels = config[6]
    if cookie_frame_length != frame_length:
        fail("afconvert produced frameLength %d, expected %d" % (cookie_frame_length, frame_length))

    pakt_body, pakt_size = by_kind["pakt"]
    pakt = data[pakt_body:pakt_body + pakt_size]
    num_packets, num_frames, _priming, _remainder = struct.unpack(">qqii", pakt[:24])

    pos = 24
    sizes = []
    for _ in range(num_packets):
        size, pos = read_varint(pakt, pos)
        sizes.append(size)

    data_body, data_size = by_kind["data"]
    payload = data[data_body + 4:data_body + data_size]  # skip edit count
    if sum(sizes) != len(payload):
        fail("packet table does not match data chunk size")

    frames = []
    offset = 0
    for size in sizes:
        frames.append(payload[offset:offset + size])
        offset += size

    counts = [declared_samples(frame, frame_length) for frame in frames]
    if sum(counts) != num_frames:
        fail("declared sample counts %d do not match packet table %d" % (sum(counts), num_frames))

    with open(os.path.join(outdir, "cookie.bin"), "wb") as f:
        f.write(cookie)
    for index, frame in enumerate(frames):
        with open(os.path.join(outdir, "frame_%04d.bin" % index), "wb") as f:
            f.write(frame)
    with open(os.path.join(outdir, "samples.txt"), "w", encoding="ascii") as f:
        for index, count in enumerate(counts):
            f.write("%d %d\n" % (index, count))

    # Pull the PCM payload out of the reference WAV rather than assuming a
    # canonical 44-byte header.
    wav_data = open(ref, "rb").read()
    if wav_data[:4] != b"RIFF" or wav_data[8:12] != b"WAVE":
        fail("reference.wav is not RIFF/WAVE")
    pos = 12
    pcm = None
    while pos + 8 <= len(wav_data):
        kind = wav_data[pos:pos + 4]
        size = struct.unpack("<I", wav_data[pos + 4:pos + 8])[0]
        body = pos + 8
        if kind == b"data":
            pcm = wav_data[body:body + size]
            break
        pos = body + size + (size & 1)
    if pcm is None:
        fail("reference.wav has no data chunk")
    expected_bytes = num_frames * channels * 2
    if len(pcm) < expected_bytes:
        fail("reference PCM is shorter than the packet table claims")
    with open(os.path.join(outdir, "expected.pcm"), "wb") as f:
        f.write(pcm[:expected_bytes])

    # Drop intermediates so repeated runs stay tidy; keep the input WAV only once.
    os.remove(caf)
    os.remove(ref)
    print("fixture %d: %d frames, %d packets, %d samples" %
          (frame_length, num_frames, num_packets, num_frames))


def main():
    if len(sys.argv) != 2:
        print("usage: alac_fixture.py <output-dir>", file=sys.stderr)
        return 2
    if not os.path.exists("/usr/bin/afconvert") and not shutil_which("afconvert"):
        fail("afconvert not found")

    outdir = os.path.abspath(sys.argv[1])
    os.makedirs(outdir, exist_ok=True)
    wav = os.path.join(outdir, "input.wav")
    make_wav(wav)

    emit_fixture(wav, outdir, 4096)

    # Reconfiguring the encoder to 352 samples per packet is a bonus check:
    # some macOS versions reject "#352", in which case the main fixture still
    # verifies the decoder.
    try:
        emit_fixture(wav, os.path.join(outdir, "fp352"), 352)
    except SystemExit:
        fp352 = os.path.join(outdir, "fp352")
        if os.path.isdir(fp352):
            for name in os.listdir(fp352):
                os.remove(os.path.join(fp352, name))
            os.rmdir(fp352)
        print("352-sample fixture unavailable on this afconvert, skipped")
    return 0


def shutil_which(name):
    for directory in os.environ.get("PATH", "").split(os.pathsep):
        candidate = os.path.join(directory, name)
        if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
            return candidate
    return None


if __name__ == "__main__":
    sys.exit(main())

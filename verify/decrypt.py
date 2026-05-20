#!/usr/bin/env python3
"""
Decrypt exported Cairn CNCE audio containers.

This is a reference helper for evidence review. The current Android app stores
recording chunks with an Android Keystore key; exported packages should rewrap
those chunks with a user-provided export password before this script can decrypt
them independently.
"""
import argparse
import struct
import sys
from pathlib import Path


MAGIC = b"CNCE"


def inspect_cnce(path: Path) -> None:
    data = path.read_bytes()
    if len(data) < 20 or data[:4] != MAGIC:
        raise SystemExit(f"not a CNCE container: {path}")

    version = data[4]
    sample_rate = struct.unpack_from("<I", data, 8)[0]
    channels = data[12]
    bits_per_sample = data[13]
    alg = data[14]
    chunk_count = struct.unpack_from("<i", data, 16)[0]

    print(f"CNCE version: {version}")
    print(f"Sample rate: {sample_rate}")
    print(f"Channels: {channels}")
    print(f"Bits/sample: {bits_per_sample}")
    print(f"Algorithm: {'AES-256-GCM' if alg == 1 else alg}")
    print(f"Chunk count: {'unknown' if chunk_count < 0 else chunk_count}")

    offset = 20
    chunks = 0
    while offset + 16 <= len(data):
        ciphertext_len = struct.unpack_from("<I", data, offset)[0]
        offset += 4 + 12 + ciphertext_len + 16
        if offset > len(data):
            raise SystemExit("truncated chunk payload")
        chunks += 1
    print(f"Readable encrypted chunks: {chunks}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Inspect a Cairn CNCE encrypted audio container")
    parser.add_argument("container", type=Path)
    args = parser.parse_args()
    inspect_cnce(args.container)
    return 0


if __name__ == "__main__":
    sys.exit(main())

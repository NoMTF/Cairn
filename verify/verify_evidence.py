#!/usr/bin/env python3
"""
Verify a merged-readable FastLink/Cairn evidence package.

Usage:
    python verify_evidence.py FastLink_diagnostics_<sessionId>.zip

The exported ZIP is intentionally readable without helper decryptors:
audio/*.wav, photos/*.jpg, gps/*.csv, sensors/*.csv, integrity/*.csv,
manifest.json, and README.md.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import struct
import sys
import zipfile
from pathlib import Path


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_manifest(zf: zipfile.ZipFile) -> dict:
    if "manifest.json" not in zf.namelist():
        raise ValueError("missing manifest.json")
    return json.loads(zf.read("manifest.json").decode("utf-8"))


def verify_manifest_files(zf: zipfile.ZipFile, manifest: dict) -> None:
    files = manifest.get("files")
    if not isinstance(files, dict) or not files:
        raise ValueError("manifest.files is missing or empty")

    names = set(zf.namelist())
    for path, meta in files.items():
        if path not in names:
            raise ValueError(f"manifest file is missing from ZIP: {path}")
        data = zf.read(path)
        expected_sha = meta.get("sha256")
        expected_size = meta.get("size_bytes")
        actual_sha = sha256(data)
        if expected_sha != actual_sha:
            raise ValueError(f"SHA-256 mismatch for {path}: {actual_sha} != {expected_sha}")
        if expected_size is not None and int(expected_size) != len(data):
            raise ValueError(f"size mismatch for {path}: {len(data)} != {expected_size}")


def verify_wav(zf: zipfile.ZipFile, manifest: dict) -> None:
    audio = manifest.get("audio") or {}
    audio_path = audio.get("file")
    if not audio_path:
        raise ValueError("manifest.audio.file is missing")
    data = zf.read(audio_path)
    if len(data) < 44:
        raise ValueError("WAV file is too small")
    if data[:4] != b"RIFF" or data[8:12] != b"WAVE" or data[12:16] != b"fmt ":
        raise ValueError("audio file is not a PCM WAV")
    if data[36:40] != b"data":
        raise ValueError("unsupported WAV layout: data chunk is not at offset 36")

    riff_size = struct.unpack_from("<I", data, 4)[0]
    fmt_size = struct.unpack_from("<I", data, 16)[0]
    audio_format = struct.unpack_from("<H", data, 20)[0]
    channels = struct.unpack_from("<H", data, 22)[0]
    sample_rate = struct.unpack_from("<I", data, 24)[0]
    bits_per_sample = struct.unpack_from("<H", data, 34)[0]
    data_size = struct.unpack_from("<I", data, 40)[0]

    if fmt_size != 16 or audio_format != 1:
        raise ValueError("WAV must be uncompressed PCM")
    if riff_size + 8 != len(data):
        raise ValueError(f"WAV RIFF size mismatch: header={riff_size + 8}, actual={len(data)}")
    if data_size != len(data) - 44:
        raise ValueError(f"WAV data size mismatch: header={data_size}, actual={len(data) - 44}")

    expected = {
        "channels": channels,
        "sample_rate": sample_rate,
        "bits_per_sample": bits_per_sample,
        "pcm_bytes": data_size,
    }
    for key, actual in expected.items():
        if key in audio and int(audio[key]) != actual:
            raise ValueError(f"manifest audio {key} mismatch: {audio[key]} != {actual}")


def verify_csv_time_order(zf: zipfile.ZipFile, path: str) -> tuple[int, int]:
    if path not in zf.namelist():
        return 0, 0
    text = zf.read(path).decode("utf-8", errors="replace").splitlines()
    if len(text) <= 1:
        return 0, 0

    broken = 0
    previous = None
    count = 0
    for row in csv.reader(text[1:]):
        if not row:
            continue
        try:
            current = int(row[0])
        except ValueError:
            continue
        if previous is not None and current < previous:
            broken += 1
        previous = current
        count += 1
    return count, broken


def verify_chain(zf: zipfile.ZipFile, path: str = "integrity/integrity_chain.csv") -> tuple[int, int]:
    if path not in zf.namelist():
        return 0, 0
    text = zf.read(path).decode("utf-8", errors="replace").splitlines()
    if len(text) <= 1:
        return 0, 0

    broken = 0
    previous_current = None
    count = 0
    for row in csv.reader(text[1:]):
        if len(row) < 4:
            continue
        prev_hash = row[2]
        current_hash = row[3]
        if previous_current is not None and prev_hash != previous_current:
            broken += 1
        previous_current = current_hash
        count += 1
    return count, broken


def verify_package(zip_path: Path) -> bool:
    if not zip_path.exists():
        raise ValueError(f"file does not exist: {zip_path}")

    with zipfile.ZipFile(zip_path, "r") as zf:
        manifest = read_manifest(zf)

        session_id = manifest.get("session_id", "<unknown>")
        print(f"Session: {session_id}")
        print(f"Format: {manifest.get('package_format', '<missing>')}")
        print(f"Export time: {manifest.get('export_time', '<missing>')}")

        verify_manifest_files(zf, manifest)
        print("OK manifest file SHA-256 entries")

        verify_wav(zf, manifest)
        print("OK playable PCM WAV")

        gps_count, gps_broken = verify_csv_time_order(zf, "gps/gps.csv")
        if gps_broken:
            raise ValueError(f"GPS timestamps moved backwards {gps_broken} times")
        print(f"OK GPS records: {gps_count}")

        sensor_count, sensor_broken = verify_csv_time_order(zf, "sensors/sensors.csv")
        if sensor_broken:
            raise ValueError(f"sensor timestamps moved backwards {sensor_broken} times")
        print(f"OK sensor records: {sensor_count}")

        chain_count, chain_broken = verify_chain(zf)
        if chain_broken:
            raise ValueError(f"integrity chain broken at {chain_broken} links")
        print(f"OK integrity chain records: {chain_count}")

        expected_counts = manifest.get("counts", {})
        if "photos" in expected_counts:
            photos = [name for name in zf.namelist() if name.startswith("photos/") and name.endswith(".jpg")]
            if len(photos) != int(expected_counts["photos"]):
                raise ValueError(f"photo count mismatch: {len(photos)} != {expected_counts['photos']}")
            print(f"OK photos: {len(photos)}")

    print("Summary: evidence package verified")
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify a merged-readable FastLink/Cairn evidence package")
    parser.add_argument("zip_path", type=Path)
    args = parser.parse_args()

    try:
        verify_package(args.zip_path)
        return 0
    except Exception as exc:
        print(f"FAILED: {exc}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

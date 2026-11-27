#!/usr/bin/env python3
"""Fetch pinned inference binaries; optionally prepare real-model test fixtures.

The production APK contains the engine only. Model weights and example audio are
downloaded into generated androidTest assets only when --test-model is requested.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tarfile
import urllib.request

ANDROID = Path(__file__).resolve().parents[1]
LOCK = ANDROID / "voice-dependencies.json"
OUTPUT = ANDROID / "build/offline-voice"
FIXTURES = ANDROID / "app/src/androidTest/assets/offline-voice"


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def matches(path: Path, spec: dict) -> bool:
    return path.is_file() and path.stat().st_size == spec["size"] and digest(path) == spec["sha256"]


def fetch(spec: dict, cache: Path) -> Path:
    cache.mkdir(parents=True, exist_ok=True)
    path = cache / spec["name"]
    if matches(path, spec):
        return path
    temporary = path.with_suffix(path.suffix + ".part")
    try:
        request = urllib.request.Request(spec["url"], headers={"User-Agent": "KunKunIME-Android-Build"})
        with urllib.request.urlopen(request, timeout=60) as response, temporary.open("wb") as sink:
            count = 0
            while chunk := response.read(1024 * 1024):
                count += len(chunk)
                if count > spec["size"]:
                    raise ValueError("Upstream download exceeded its pinned size")
                sink.write(chunk)
        if not matches(temporary, spec):
            raise ValueError(f"SHA-256 or size mismatch: {spec['name']}")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)
    return path


def prepare_fixtures(spec: dict, cache: Path) -> None:
    archive = fetch(spec, cache)
    FIXTURES.mkdir(parents=True, exist_ok=True)
    wanted = [*spec["files"], "test_wavs/zh.wav", "test_wavs/en.wav"]
    with tarfile.open(archive, "r:bz2") as bundle:
        for relative in wanted:
            member = bundle.getmember(f"{spec['archive_root']}/{relative}")
            if not member.isfile():
                raise ValueError(f"Not a regular fixture: {relative}")
            output = FIXTURES / relative
            output.parent.mkdir(parents=True, exist_ok=True)
            with bundle.extractfile(member) as source, output.open("wb") as sink:
                shutil.copyfileobj(source, sink)
            if relative in spec["files"] and not matches(output, spec["files"][relative]):
                raise ValueError(f"Extracted fixture integrity mismatch: {relative}")
    print("Prepared verified SenseVoice model and upstream Chinese/English recordings for instrumentation only")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--test-model", action="store_true")
    parser.add_argument("--cache", type=Path, default=OUTPUT)
    args = parser.parse_args()
    config = json.loads(LOCK.read_text())
    engine = fetch(config["engine"], args.cache)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    destination = OUTPUT / config["engine"]["name"]
    if engine.resolve() != destination.resolve():
        shutil.copy2(engine, destination)
    print(f"Verified sherpa-onnx {config['engine']['version']} AAR")
    if args.test_model:
        prepare_fixtures(config["model"], args.cache)


if __name__ == "__main__":
    main()

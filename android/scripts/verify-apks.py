#!/usr/bin/env python3
"""Check the actual phone/emulator APK contents, rather than trusting filenames."""

import argparse
import hashlib
from pathlib import Path
import re
from zipfile import ZipFile


ANDROID_ROOT = Path(__file__).resolve().parents[1]
DEBUG_APKS = ANDROID_ROOT / "app/build/outputs/apk/debug"
ELF_MACHINE = {"arm64-v8a": 183, "x86_64": 62}


def inspect_apk(path: Path, expected_abi: str) -> dict[str, str]:
    with ZipFile(path) as apk:
        names = {entry.filename for entry in apk.infolist() if not entry.is_dir()}
        native_names = sorted(name for name in names if name.startswith("lib/"))
        actual_abis = {name.split("/")[1] for name in native_names}
        if actual_abis != {expected_abi}:
            raise ValueError(f"{path.name}: expected only {expected_abi}, found {sorted(actual_abis)}")

        # The engine and its JNA bridge must both exist for this architecture.
        for library in ("libwind_android.so", "libjnidispatch.so"):
            if f"lib/{expected_abi}/{library}" not in names:
                raise ValueError(f"{path.name}: missing {library} for {expected_abi}")
        for name in native_names:
            if not name.endswith(".so"):
                raise ValueError(f"{path.name}: unexpected native-library entry {name}")
            if re.fullmatch(r"libredb(?:-.*)?\.so", Path(name).name):
                raise ValueError(f"{path.name}: unused redb cdylib was packaged: {name}")
            with apk.open(name) as library:
                header = library.read(20)
            if len(header) != 20 or header[:4] != b"\x7fELF" or header[4] != 2:
                raise ValueError(f"{path.name}: not a 64-bit ELF library: {name}")
            byteorder = {1: "little", 2: "big"}.get(header[5])
            if byteorder is None or int.from_bytes(header[18:20], byteorder) != ELF_MACHINE[expected_abi]:
                raise ValueError(f"{path.name}: ELF architecture does not match {expected_abi}: {name}")

        if "classes.dex" not in names or "resources.arsc" not in names:
            raise ValueError(f"{path.name}: app code or resources are missing")
        # ABI splits must carry the same Kotlin code, dictionaries and UI. A
        # different signing certificate does not affect these payload hashes.
        shared_payload = {
            name: hashlib.sha256(apk.read(name)).hexdigest()
            for name in names
            if re.fullmatch(r"classes\d*\.dex", name)
            or name == "resources.arsc"
            or name.startswith(("assets/", "res/"))
        }
        print(f"{path.name}: {path.stat().st_size:,} bytes; only {expected_abi}; "
              f"{len(native_names)} valid native libraries")
        return shared_payload


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phone-apk", type=Path, default=DEBUG_APKS / "app-arm64-v8a-debug.apk")
    parser.add_argument("--emulator-apk", type=Path, default=DEBUG_APKS / "app-x86_64-debug.apk")
    args = parser.parse_args()
    try:
        phone_payload = inspect_apk(args.phone_apk, "arm64-v8a")
        emulator_payload = inspect_apk(args.emulator_apk, "x86_64")
        changed = sorted(name for name in phone_payload.keys() | emulator_payload.keys()
                         if phone_payload.get(name) != emulator_payload.get(name))
        if changed:
            raise ValueError(f"Phone and emulator app payloads differ: {changed[:5]}")
    except (OSError, ValueError) as error:
        parser.exit(1, f"APK verification failed: {error}\n")
    print(f"Phone and emulator contain the same {len(phone_payload)} code/resource/asset files.")


if __name__ == "__main__":
    main()

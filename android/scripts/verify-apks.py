#!/usr/bin/env python3
"""Check the actual phone/emulator APK contents, rather than trusting filenames."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
from zipfile import ZipFile


ANDROID_ROOT = Path(__file__).resolve().parents[1]
APK_OUTPUT = ANDROID_ROOT / "app/build/outputs/apk"
ELF_MACHINE = {"arm64-v8a": 183, "x86_64": 62}
APP_ID = "io.github.shuimowang.kunkunime"


def built_apk(variant: str, abi: str) -> Path:
    folder = APK_OUTPUT / variant
    metadata = json.loads((folder / "output-metadata.json").read_text())
    if metadata["applicationId"] != APP_ID:
        raise ValueError("Unexpected application ID in Gradle output metadata")
    matches = [item for item in metadata["elements"]
               if {f["value"] for f in item["filters"] if f["filterType"] == "ABI"} == {abi}]
    if len(matches) != 1:
        raise ValueError(f"Expected one {abi} output in {variant} metadata")
    return folder / matches[0]["outputFile"]


def release_manifest(path: Path, aapt2: Path) -> dict[str, object]:
    badging = subprocess.check_output([str(aapt2), "dump", "badging", str(path)], text=True)
    tree = subprocess.check_output(
        [str(aapt2), "dump", "xmltree", str(path), "--file", "AndroidManifest.xml"], text=True,
    )
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging, re.M)
    minimum = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", badging, re.M)
    target = re.search(r"^targetSdkVersion:'(\d+)'", badging, re.M)
    if package is None or minimum is None or target is None or package[1] != APP_ID:
        raise ValueError(f"{path.name}: could not verify the app identity and SDK levels")
    for attribute in ("debuggable", "testOnly"):
        values = re.findall(rf"android:{attribute}\([^)]*\)=(.*)$", tree, re.M)
        for value in values:
            boolean = re.fullmatch(r"\(type 0x12\)0x([0-9a-fA-F]+)\s*", value)
            if boolean is None or int(boolean[1], 16) != 0:
                raise ValueError(f"{path.name}: release must not have {attribute}=true")
    if "application-debuggable" in badging:
        raise ValueError(f"{path.name}: a debuggable APK cannot be distributed as release")
    if int(minimum[1]) != 36 or int(target[1]) != 36:
        raise ValueError(f"{path.name}: expected minSdk/targetSdk 36")
    return dict(application_id=package[1], version_code=int(package[2]), version_name=package[3],
                min_sdk=int(minimum[1]), target_sdk=int(target[1]), debuggable=False, test_only=False)


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
    parser.add_argument("--variant", choices=("debug", "release"), default="debug")
    parser.add_argument("--phone-apk", type=Path)
    parser.add_argument("--emulator-apk", type=Path)
    parser.add_argument("--aapt2", type=Path, help="required for release manifest checks")
    parser.add_argument("--write-release-info", type=Path, help="write verified release metadata for publication")
    parser.add_argument("--cargo-sources", type=Path, help="locked source archive to record in release metadata")
    args = parser.parse_args()
    if args.variant == "release" and args.aapt2 is None:
        parser.error("--aapt2 is required when checking release APKs")
    if args.write_release_info and args.variant != "release":
        parser.error("Release metadata can only describe verified release APKs")
    if args.write_release_info and args.cargo_sources is None:
        parser.error("--cargo-sources is required for publication metadata")
    try:
        phone = args.phone_apk or built_apk(args.variant, "arm64-v8a")
        emulator = args.emulator_apk or built_apk(args.variant, "x86_64")
        phone_payload = inspect_apk(phone, "arm64-v8a")
        emulator_payload = inspect_apk(emulator, "x86_64")
        changed = sorted(name for name in phone_payload.keys() | emulator_payload.keys()
                         if phone_payload.get(name) != emulator_payload.get(name))
        if changed:
            raise ValueError(f"Phone and emulator app payloads differ: {changed[:5]}")
        if args.variant == "release":
            info = release_manifest(phone, args.aapt2)
            if release_manifest(emulator, args.aapt2) != info:
                raise ValueError("Release APK identities or SDK levels differ")
            # Compare the actual manifest against this build's version metadata.
            for metadata_item in json.loads((APK_OUTPUT / "release/output-metadata.json").read_text())["elements"]:
                if (metadata_item["versionCode"], metadata_item["versionName"]) != (info["version_code"], info["version_name"]):
                    raise ValueError("APK version differs from Gradle output metadata")
            info.update(build_type="release", git_commit=subprocess.check_output(
                ["git", "rev-parse", "HEAD"], cwd=ANDROID_ROOT, text=True,
            ).strip())
            info["apks"] = [dict(name=path.name, abi=abi, size=path.stat().st_size,
                                sha256=hashlib.sha256(path.read_bytes()).hexdigest())
                            for path, abi in ((phone, "arm64-v8a"), (emulator, "x86_64"))]
            if args.cargo_sources:
                with args.cargo_sources.open("rb") as stream:
                    info["cargo_sources"] = dict(name=args.cargo_sources.name,
                                                sha256=hashlib.file_digest(stream, "sha256").hexdigest())
            if args.write_release_info:
                args.write_release_info.parent.mkdir(parents=True, exist_ok=True)
                args.write_release_info.write_text(json.dumps(info, indent=2) + "\n")
            print(f"Verified release {info['version_name']} ({info['version_code']}): non-debuggable, not test-only.")
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"APK verification failed: {error}\n")
    print(f"Phone and emulator contain the same {len(phone_payload)} code/resource/asset files.")


if __name__ == "__main__":
    main()

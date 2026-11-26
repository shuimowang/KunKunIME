#!/usr/bin/env python3
"""Sign a tested APK with an external private key and the pinned public certificate.

The private keystore and its password file must be kept outside the repository.
This script never reads or prints the password. Android's signing tool reads the
password file directly; no secret is passed in command-line arguments.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile


def run(command: list[str]) -> str:
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(f"{Path(command[0]).name} failed ({result.returncode}):\n{result.stdout}")
    return result.stdout


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def certificate_digest(path: Path) -> str:
    blocks = re.findall(
        r"-----BEGIN CERTIFICATE-----\s*(.*?)\s*-----END CERTIFICATE-----",
        path.read_text(encoding="ascii"),
        re.DOTALL,
    )
    if len(blocks) != 1:
        raise ValueError("Expected exactly one public signing certificate")
    der = base64.b64decode(re.sub(r"\s+", "", blocks[0]), validate=True)
    return hashlib.sha256(der).hexdigest()


def payload(path: Path) -> dict[str, str]:
    """Include every ZIP entry except the previous JAR signature metadata.

    APK v2/v3 signing blocks are outside the ZIP entries. License/other META-INF
    files are still compared, as are the manifest, DEX, assets and native libs.
    """
    hashes: dict[str, str] = {}
    with zipfile.ZipFile(path) as archive:
        seen: set[str] = set()
        for entry in archive.infolist():
            if entry.filename in seen:
                raise ValueError(f"Duplicate APK entry: {entry.filename}")
            seen.add(entry.filename)
            if entry.is_dir():
                continue
            if re.fullmatch(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))", entry.filename, re.IGNORECASE):
                continue
            with archive.open(entry) as stream:
                hashes[entry.filename] = hashlib.file_digest(stream, "sha256").hexdigest()
    return hashes


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True,
                        help="Android SDK build-tools directory containing zipalign and lib/apksigner.jar")
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--password-file", type=Path, required=True,
                        help="Private file containing the keystore/key password on its first line")
    parser.add_argument("--alias", default="kunkun-android")
    parser.add_argument("--certificate", type=Path,
                        default=Path(__file__).resolve().parents[1] / "signing/kunkun-android-cert.pem")
    args = parser.parse_args()

    source, destination = args.input.resolve(), args.output.resolve()
    if destination == source or destination.exists():
        parser.error("Output must be a new path; the tested input APK is never overwritten")
    zipalign = args.build_tools.resolve() / ("zipalign.exe" if os.name == "nt" else "zipalign")
    signer = args.build_tools.resolve() / "lib/apksigner.jar"
    for file in (source, zipalign, signer, args.keystore, args.password_file, args.certificate):
        if not file.is_file():
            parser.error(f"Missing file: {file}")
    expected_certificate = certificate_digest(args.certificate)
    original_payload = payload(source)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="kunkun-sign-", dir=destination.parent) as scratch:
        aligned = Path(scratch) / "aligned.apk"
        signed = Path(scratch) / "signed.apk"
        # Preserve Android's 16 KiB native-library alignment before signing.
        run([str(zipalign), "-P", "16", "-f", "4", str(source), str(aligned)])
        run([
            "java", "-jar", str(signer), "sign",
            "--ks", str(args.keystore.resolve()),
            "--ks-key-alias", args.alias,
            "--ks-pass", f"file:{args.password_file.resolve()}",
            # PKCS12 uses the store password for its key. Giving the same file
            # as --key-pass would consume a second line from that file.
            "--v1-signing-enabled", "false",  # The Android 16 baseline supports APK signature v2.
            "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
            "--v4-signing-enabled", "false",
            "--out", str(signed), str(aligned),
        ])
        verification = run(["java", "-jar", str(signer), "verify", "--verbose", "--print-certs", str(signed)])
        # Build Tools 37 reports each scheme as "V2 Signer:" / "V3 Signer:";
        # older versions use "Signer #1". All reported certificates must match.
        certificates = re.findall(r"^.+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$",
                                  verification, re.MULTILINE)
        if (set(digest.lower() for digest in certificates) != {expected_certificate}
                or not re.search(r"^Number of signers: 1$", verification, re.MULTILINE)):
            raise RuntimeError("APK signer does not match the pinned public certificate")
        run([str(zipalign), "-c", "-P", "16", "4", str(signed)])
        if original_payload != payload(signed):
            raise RuntimeError("Signing changed APK payload; refusing to publish")
        os.replace(signed, destination)

    print(json.dumps({
        "output": str(destination),
        "bytes": destination.stat().st_size,
        "sha256": sha256(destination),
        "certificate_sha256": expected_certificate,
        "signature_verified": True,
        "payload_unchanged": True,
        "native_alignment": "16 KiB",
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()

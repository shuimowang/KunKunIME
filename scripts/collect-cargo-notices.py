"""Collect the license texts for KunKunIME's locked Windows Cargo dependency graph."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "WindInput/wind_input/Cargo.toml"
TARGET = "x86_64-pc-windows-msvc"
ZUNE_COMMITS = {
    ("zune-core", "0.4.12"): "f8fbb123d5ed04441e8324a555bfcda0cb1bd28f",
    ("zune-jpeg", "0.4.21"): "fa2c767a01d7d9373911d0bf63e0588553d67e0e",
}
NOTICE = re.compile(
    r"^(?:licen[cs]es?|copying|copyright|notices?|unlicense|authors|patents|ofl|"
    r"third[-_ ]party[-_ ](?:licen[cs]es?|notices?|attributions?))(?:$|[ ._-])", re.I
)


def run(args, env):
    return subprocess.check_output(args, cwd=ROOT, env=env).decode("utf-8").strip()


def select_toolchain():
    env = os.environ.copy()
    cargo = shutil.which("cargo")
    if cargo is None:
        local = ROOT / "artifacts/toolchain"
        exe = local / "rust/bin/cargo.exe"
        if not exe.is_file():
            raise RuntimeError("Cargo is required to resolve the release build.")
        cargo = str(exe)
        env["PATH"] = str(exe.parent) + os.pathsep + env["PATH"]
        env["CARGO_HOME"] = str(local / "cargo")
    return cargo, env


def dependencies(cargo, env, offline):
    flags = ["--manifest-path", str(MANIFEST), "--locked"]
    if offline:
        flags.append("--offline")
    metadata = json.loads(run([cargo, "metadata", *flags, "--format-version", "1",
                               "--filter-platform", TARGET], env))
    tree = run([cargo, "tree", *flags, "--target", TARGET, "-p", "wind_service",
                "--edges", "normal,build", "--prefix", "none", "--format", "{p}"], env)
    keys = set()
    for line in tree.splitlines():
        match = re.match(r"^([A-Za-z0-9_-]+) v([^\s]+)(?:\s|$)", line)
        if match:
            keys.add(match.groups())
        elif line.strip() and not line.strip().startswith("["):
            raise RuntimeError(f"Unrecognized Cargo package: {line}")
    selected = [p for p in metadata["packages"] if (p["name"], p["version"]) in keys]
    if len(selected) != len(keys):
        raise RuntimeError("Ambiguous dependency source; review the resolved Cargo graph.")
    return sorted(selected, key=lambda p: (p["name"], p["version"]))


def notice_files(package, folder):
    files = set()
    if package.get("license_file"):
        declared = (folder / package["license_file"]).resolve()
        if not declared.is_relative_to(folder) or not declared.is_file():
            raise RuntimeError(f"Missing declared license file for {package['name']}")
        files.add(declared)
    for f in folder.rglob("*"):
        if not f.is_file() or f.is_symlink():
            continue
        relative = f.relative_to(folder)
        if NOTICE.match(f.name) or any(n.lower() in {"licenses", "licences"} for n in relative.parts[:-1]):
            files.add(f)
    return sorted(files)


def collect(output, archive_path, offline):
    cargo, env = select_toolchain()
    packages = dependencies(cargo, env, offline)
    locked = {(p["name"], p["version"]): p.get("checksum") for p in
              tomllib.loads(MANIFEST.with_name("Cargo.lock").read_text(encoding="utf-8"))["package"]}
    rows, archives = [], []
    texts = output / "texts"
    texts.mkdir(parents=True, exist_ok=True)
    for p in packages:
        folder = Path(p["manifest_path"]).resolve().parent
        external = p["source"] is not None
        if not external and not folder.is_relative_to(ROOT):
            raise RuntimeError(f"Path dependency outside the product source: {p['name']}")
        key = (p["name"], p["version"])
        versioned = f"{p['name']}-{p['version']}"
        if external:
            if p["source"] != "registry+https://github.com/rust-lang/crates.io-index" or not locked.get(key):
                raise RuntimeError(f"Review non-crates.io dependency {versioned}")
            source_url = f"https://static.crates.io/crates/{p['name']}/{versioned}.crate"
            row = dict(name=p["name"], version=p["version"], license=p["license"],
                       source=source_url, source_sha256=locked[key])
            files = notice_files(p, folder)
            if archive_path:
                # Cargo's registry/{src,cache}/<registry-id>/ directories are siblings.
                archives.append((folder.parents[2] / "cache" / folder.parent.name /
                                 (versioned + ".crate"), locked[key]))
        else:
            row = dict(name=p["name"], version=p["version"], license=p["license"],
                       source=folder.relative_to(ROOT).as_posix())
            if folder.is_relative_to(ROOT / "wind-setting/vendor"):
                files = notice_files(p, folder)
            elif folder.is_relative_to(ROOT / "WindInput"):
                files = [ROOT / "WindInput/LICENSE"]
            elif folder == ROOT / "wind-setting":
                files = [folder / "LICENSE"]
            elif folder == ROOT / "wind-ui-rust":
                files = [folder / "LICENSE-APACHE", folder / "LICENSE-MIT"]
            else:
                raise RuntimeError(f"Review local license for {row['source']}")
        override = None
        if not files and key in ZUNE_COMMITS:
            commit = ZUNE_COMMITS[key]
            vcs = json.loads((folder / ".cargo_vcs_info.json").read_text(encoding="utf-8"))
            if vcs["git"]["sha1"] != commit:
                raise RuntimeError(f"Upstream license pin changed for {versioned}")
            override = Path(__file__).parent.resolve() / "license-overrides/zune-image"
            files = [override / "LICENSE.md", override / "LICENSE-ZLIB"]
            row["notice_source"] = f"https://github.com/etemesi254/zune-image/tree/{commit}"
        if not files:
            raise RuntimeError(f"Review missing third-party notice for {versioned}")
        row["notices"] = {}
        for f in files:
            data = f.read_bytes()
            digest = hashlib.sha256(data).hexdigest()
            rel = "texts/" + digest + ".txt"
            if not (output / rel).exists():
                (output / rel).write_bytes(data)
            base = override or (folder if f.is_relative_to(folder) else ROOT)
            row["notices"][f.relative_to(base).as_posix()] = rel
        rows.append(row)

    rustc = shutil.which("rustc", path=env["PATH"])
    if not rustc:
        raise RuntimeError("rustc is required for the bundled standard-library notices")
    sysroot = Path(run([rustc, "--print", "sysroot"], env)).resolve()
    rust_docs = sysroot / "share/doc/rust"
    std_dest = output / "rust-stdlib"
    std_dest.mkdir(exist_ok=True)
    shutil.copyfile(rust_docs / "COPYRIGHT-library.html", std_dest / "COPYRIGHT-library.html")
    shutil.copytree(rust_docs / "licenses", std_dest / "licenses", dirs_exist_ok=True)
    index = dict(target=TARGET, root_package="wind_service",
                 rust_compiler=run([rustc, "-vV"], env), crates=rows)
    (output / "index.json").write_text(json.dumps(index, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    lines = ["# Rust third-party notices", "", f"Windows target: {TARGET}.",
             "This inventory includes normal and build dependencies of the locked application, including procedural macros.",
             "The index records their declared licenses, sources, and original notice file names. Identical notice texts are shared.",
             "Published crate versions link to their exact source archives, including MPL-covered sources.",
             "Local packages come from the corresponding KunKunIME source release.",
             "See [Rust standard-library notices](rust-stdlib/COPYRIGHT-library.html) for the toolchain's runtime libraries.",
             "", "| Package | License | Notices |", "|---|---|---|"]
    for row in rows:
        label = f"{row['name']} {row['version']}"
        if row["source"].startswith("https://"):
            label = f"[{label}]({row['source']})"
        refs = ", ".join(f"[{k}]({v})" for k, v in row["notices"].items())
        lines.append(f"| {label} | {row['license'] or 'See notices'} | {refs} |")
    (output / "README.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    if archive_path:
        archive_path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=archive_path.parent, delete=False) as tmp:
            staged = Path(tmp.name)
        try:
            with zipfile.ZipFile(staged, "w", allowZip64=True) as z:
                for f, sha in archives:
                    data = f.read_bytes()
                    if hashlib.sha256(data).hexdigest() != sha:
                        raise RuntimeError(f"Registry archive checksum mismatch: {f.name}")
                    z.writestr("crates/" + f.name, data)
                z.write(MANIFEST.with_name("Cargo.lock"), "Cargo.lock")
                z.write(output / "index.json", "index.json")
            staged.replace(archive_path)
        finally:
            staged.unlink(missing_ok=True)
    print(f"Validated notices for {len(rows)} Cargo packages and Rust's standard library.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--crate-sources", type=Path, help="also archive locked registry source files")
    args = parser.parse_args()
    collect(args.output.resolve(), args.crate_sources, args.offline)


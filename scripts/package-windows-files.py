"""Inventory a clean Windows bundle, then copy or archive exactly its packaged files."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import tempfile
import zipfile


MANIFEST = "uninstall-manifest.json"
ROOT_FILES = {"KunKunIME.exe", "kunkun_tsf.dll", "kunkun_tsf_x86.dll", "使用说明.md", "MCP.md", "卸载Windows输入法注册.md"}
ROOT_DIRS = {"data", "data_custom", "licenses"}


def checked_path(path):
    """Reject redirects even in an existing destination's parent directories."""
    for part in (path, *path.parents):
        try:
            info = part.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT:
            raise ValueError(f"Link in package path: {part}")
    return path


def inventory(stage):
    entries = []
    pending = list(stage.iterdir())
    while pending:
        path = checked_path(pending.pop())
        rel = path.relative_to(stage)
        if rel.as_posix() == MANIFEST and path.is_file():
            continue
        if rel.parts[0] not in ROOT_DIRS and rel.as_posix() not in ROOT_FILES:
            raise ValueError(f"Unexpected entry in clean package staging directory: {rel}")
        if path.is_dir():
            pending.extend(path.iterdir())
        elif path.is_file():
            with path.open("rb") as source:
                digest = hashlib.file_digest(source, "sha256").hexdigest()
            entries.append({"path": rel.as_posix(), "sha256": digest})
        else:
            raise ValueError(f"Not a regular package file: {rel}")
    if not ROOT_FILES.issubset(entry["path"] for entry in entries) or not all((stage / d).is_dir() for d in ROOT_DIRS):
        raise ValueError("Staging directory is missing required Windows package files")
    return sorted(entries, key=lambda entry: entry["path"])


def replace_file(destination, write):
    with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as temp:
        path = Path(temp.name)
    try:
        write(path)
        path.replace(destination)
    finally:
        path.unlink(missing_ok=True)


def bundle(stage, output=None, archive=None):
    stage = checked_path(Path(os.path.abspath(stage)))
    entries = inventory(stage)
    names = [e["path"] for e in entries] + [MANIFEST]
    if output is not None:
        output = checked_path(Path(os.path.abspath(output)))
        if output.is_relative_to(stage) or stage.is_relative_to(output):
            raise ValueError("Staging and output directories must be separate")
        for name in names:
            dest = checked_path(output / name)
            if dest.exists() and not dest.is_file():
                raise ValueError(f"Output file path is occupied: {dest}")
    if archive is not None:
        archive = checked_path(Path(os.path.abspath(archive)))
        if archive.is_relative_to(stage) or output is not None and archive.is_relative_to(output):
            raise ValueError("Archive must be outside the package directories")

    manifest = {"product": "KunKunIME", "files": entries}
    replace_file(stage / MANIFEST, lambda p: p.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"))
    if archive is not None:
        def write_zip(path):
            with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as package:
                for name in names:
                    package.write(stage / name, f"困困输入法/{name}")
        replace_file(archive, write_zip)
    if output is not None:
        for name in names:
            dest = output / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            replace_file(dest, lambda p: shutil.copyfile(stage / name, p))
    return len(entries)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("staging", type=Path, help="clean bundle assembled from build outputs or extracted from an official ZIP")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--archive", type=Path)
    args = parser.parse_args()
    print(f"Packaged files: {bundle(args.staging, args.output, args.archive)}")

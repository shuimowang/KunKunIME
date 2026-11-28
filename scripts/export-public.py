"""Export a new, reviewable Windows source tree without local history or personal state."""
from pathlib import Path, PurePosixPath
import argparse
import json
import shutil
import subprocess

SOURCE = Path(__file__).resolve().parents[1]
ROOT_FILES = {".gitignore", ".gitattributes", "README.md", "ORIGINS.md", "LICENSE", "SECURITY.md", "CONTRIBUTING.md"}
BUILD_SCRIPTS = {
    "build-core.ps1", "build-tsf.ps1", "build-windows.ps1", "test-tsf.ps1",
    "test-typing.ps1", "卸载Windows输入法注册.md", "uninstall-windows.ps1", "generate-english.py",
    "collect-cargo-notices.py", "package-windows-files.py", "uninstall-windows.ps1", "export-public.py",
}


def public_path(name):
    p = PurePosixPath(name)
    parts = p.parts
    if not parts or any(part.lower() in {".git", "target", "artifacts", "dist", ".cache", ".backup", "userdata", "localdata"} for part in parts):
        return False
    if p.name in {"AGENTS.md", "CLAUDE.md"}:
        return False
    if len(parts) == 1:
        return name in ROOT_FILES
    if parts[0] == "scripts":
        return len(parts) == 2 and p.name in BUILD_SCRIPTS or parts[1] == "license-overrides"
    if parts[0] in {"assets", "licenses", "tests"}:
        return True
    if parts[0] == "docs":
        return parts[1:] in {("使用说明.md",), ("MCP.md",)}
    if parts[0] == "WindInput":
        if name.startswith("WindInput/scripts/lm/"):
            return True
        if parts[1] in {"wind_input", "data_custom"}:
            return True
        if parts[1] == "data":
            return not (
                name.startswith(("WindInput/data/schemas/wubi", "WindInput/data/comments-src/"))
                or p.name in {"shouyou.txt", "shouyou_plus.txt"}
            )
        if parts[1] == "wind_tsf":
            return p.name != "wind_input.ico" and p.suffix.lower() != ".md"
        return name in {"WindInput/docs/VERSION", "WindInput/LICENSE", "WindInput/NOTICE.md", "WindInput/BRANDING.md"}
    if parts[0] == "wind-setting":
        return (parts[1] in {"src", "vendor"} or name.startswith(("wind-setting/res/icons/ui/", "wind-setting/res/xdg/kunkunime"))
                or name in {"wind-setting/Cargo.toml", "wind-setting/Cargo.lock", "wind-setting/build.rs", "wind-setting/LICENSE",
                            "wind-setting/README.md", "wind-setting/.gitattributes"})
    if parts[0] == "wind-ui-rust":
        return (parts[1] in {"src", "i18n", "examples"} or name in
                {"wind-ui-rust/Cargo.toml", "wind-ui-rust/build.rs", "wind-ui-rust/README.md",
                 "wind-ui-rust/LICENSE-MIT", "wind-ui-rust/LICENSE-APACHE"})
    return name.startswith(".github/workflows/")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    destination = parser.parse_args().destination.resolve()
    if destination == SOURCE or SOURCE in destination.parents:
        raise SystemExit("Choose an empty directory outside the working tree.")
    if destination.exists() and any(destination.iterdir()):
        raise SystemExit("Destination contains files; creating a clean export requires an empty directory.")
    candidates = subprocess.check_output(["git", "-C", str(SOURCE), "ls-files", "--cached", "--others", "--exclude-standard", "-z"])
    files = sorted({s.decode("utf8") for s in candidates.split(b"\0") if s and public_path(s.decode("utf8"))})
    if any(not (SOURCE / f).is_file() or (SOURCE / f).is_symlink() for f in files):
        raise SystemExit("Review unexpected missing files or symbolic links before exporting.")
    required = {"LICENSE", "ORIGINS.md", "licenses/THIRD-PARTY.md", "licenses/data/CMUdict.txt", "scripts/build-windows.ps1",
                "assets/kunkun.ico", "wind-setting/src/lib.rs", "WindInput/wind_input/Cargo.lock",
                "WindInput/data/schemas/english/en.dict.yaml", "WindInput/data/schemas/pinyin/ext/honor_of_kings.dict.yaml"}
    if required.difference(files):
        raise SystemExit("Missing required files: " + ", ".join(sorted(required.difference(files))))
    english = (SOURCE / "WindInput/data/schemas/english/en.dict.yaml").read_text(encoding="utf-8-sig")
    if "# KunKunIME English dictionary" not in english[:200] or "first20hours" in english:
        raise SystemExit("Build the redistributable English dictionary before exporting.")
    destination.mkdir(parents=True, exist_ok=True)
    for name in files:
        dst = destination / name
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(SOURCE / name, dst)
    print(json.dumps({"directory":str(destination),"files":len(files),"history":"none"}, ensure_ascii=False))


if __name__ == "__main__":
    main()


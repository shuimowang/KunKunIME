#!/usr/bin/env python3
"""Publish checked Windows archives, or prepare an Android signing draft.

Run only after the build/test jobs for the same commit have succeeded. The
workflow grants this job contents:write; build and pull-request jobs stay read-only.
Android's private signing key is intentionally not required by this script.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?\Z")
SHA = re.compile(r"[0-9a-f]{40}\Z")


def gh(*arguments: str, stdin: str | None = None, missing_ok: bool = False):
    result = subprocess.run(
        ["gh", *arguments], input=stdin, text=True, capture_output=True, check=False,
    )
    if result.returncode:
        if missing_ok and "(HTTP 404)" in result.stderr:
            return None
        raise RuntimeError(f"GitHub command failed: {result.stderr.strip()}")
    return result.stdout


def api(path: str, payload: dict | None = None, missing_ok: bool = False):
    command = ["api", "--hostname", "github.com", path]
    if payload is not None:
        command += ["--method", "POST", "--input", "-"]
    output = gh(*command, stdin=json.dumps(payload) if payload is not None else None,
                missing_ok=missing_ok)
    return json.loads(output) if output is not None else None


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def windows_files(directory: Path, version: str) -> dict[str, dict]:
    expected = {f"KunKunIME-{version}-Windows-x64.zip",
                f"KunKunIME-{version}-Cargo-sources.zip"}
    checksums = directory / "SHA256SUMS.txt"
    if checksums.is_symlink() or not checksums.is_file():
        raise ValueError("Missing regular SHA256SUMS.txt")
    declared = {}
    for line in checksums.read_text(encoding="utf-8-sig").splitlines():
        match = re.fullmatch(r"([0-9a-fA-F]{64})  (.+)", line)
        if not match or match[2] not in expected or match[2] in declared:
            raise ValueError("Unexpected, duplicate or malformed checksum entry")
        declared[match[2]] = match[1].lower()
    if set(declared) != expected:
        raise ValueError("The program and matching Cargo sources must both be present")
    files = {}
    for name in sorted(expected | {checksums.name}):
        path = directory / name
        if path.is_symlink() or not path.is_file() or path.stat().st_size == 0:
            raise ValueError(f"Missing or invalid release file: {name}")
        actual = digest(path)
        if name in declared and declared[name] != actual:
            raise ValueError(f"Checksum mismatch: {name}")
        files[name] = {"path": path.resolve(), "size": path.stat().st_size,
                       "digest": f"sha256:{actual}"}
    return files


def tag_commit(repository: str, tag: str) -> str | None:
    reference = api(f"repos/{repository}/git/ref/tags/{tag}", missing_ok=True)
    if reference is None:
        return None
    obj = reference["object"]
    for _ in range(8):
        if obj["type"] == "commit":
            return obj["sha"]
        if obj["type"] != "tag":
            break
        obj = api(f"repos/{repository}/git/tags/{obj['sha']}")["object"]
    raise ValueError("Release tag does not resolve to a commit")


def ensure_tag(repository: str, tag: str, source_sha: str) -> None:
    existing = tag_commit(repository, tag)
    if existing is None:
        api(f"repos/{repository}/git/refs", {
            "ref": f"refs/tags/{tag}", "sha": source_sha,
        })
        existing = tag_commit(repository, tag)
    if existing != source_sha:
        raise ValueError(f"Tag {tag} points to another commit; refusing to move it")


def find_release(repository: str, tag: str) -> dict | None:
    # The authenticated list includes unpublished drafts as well as releases.
    page = 1
    while True:
        releases = api(f"repos/{repository}/releases?per_page=100&page={page}")
        for release in releases:
            if release["tag_name"] == tag:
                return release
        if len(releases) < 100:
            return None
        page += 1


def verify_assets(repository: str, tag: str, release: dict,
                  files: dict[str, dict]) -> None:
    assets = release["assets"]
    if len(assets) != len(files) or {a["name"] for a in assets} != set(files):
        raise ValueError("Release asset list differs from the verified files")
    for asset in assets:
        expected = files[asset["name"]]
        if asset["state"] != "uploaded" or asset["size"] != expected["size"]:
            raise ValueError(f"Incomplete release upload: {asset['name']}")
        if asset.get("digest") and asset["digest"] != expected["digest"]:
            raise ValueError(f"Uploaded digest mismatch: {asset['name']}")
    if any(not a.get("digest") for a in assets):
        # Older GitHub responses may lack server digests. Verify the actual bytes.
        with tempfile.TemporaryDirectory(prefix="kunkun-release-check-") as temp:
            command = ["release", "download", tag, "--repo", f"github.com/{repository}",
                       "--dir", temp]
            for name in files:
                command += ["--pattern", name]
            gh(*command)
            for name, expected in files.items():
                if "sha256:" + digest(Path(temp) / name) != expected["digest"]:
                    raise ValueError(f"Downloaded release digest mismatch: {name}")


def publish(repository: str, tag: str, source_sha: str, title: str, notes: Path,
            files: dict[str, dict], draft_only: bool, prerelease: bool) -> dict:
    ensure_tag(repository, tag, source_sha)
    release = find_release(repository, tag)
    if release and not release["draft"]:
        # A publish-job retry uses the original build artifact. A new/different
        # build cannot silently replace or impersonate the public version.
        if files:
            verify_assets(repository, tag, release, files)
        return {"status": "already_published", "url": release["html_url"], "tag": tag}
    if release is None:
        command = ["release", "create", tag, "--repo", f"github.com/{repository}",
                   "--verify-tag", "--target", source_sha, "--draft", "--latest=false",
                   "--title", title, "--notes-file", str(notes)]
        if prerelease:
            command.append("--prerelease")
        gh(*command)
        release = find_release(repository, tag)
        # A successful create can precede visibility in the release list. Only
        # retry this read, never creation or a response that is already visible.
        for delay in (1, 2, 4, 8):
            if release is not None:
                break
            time.sleep(delay)
            release = find_release(repository, tag)
    if not release or not release["draft"]:
        raise ValueError("Expected an unpublished draft")
    if release["target_commitish"] != source_sha:
        raise ValueError("Existing draft targets a different commit")
    if draft_only:
        return {"status": "awaiting_signed_android_apk", "url": release["html_url"],
                "tag": tag, "source_sha": source_sha}
    unknown = {a["name"] for a in release["assets"]} - set(files)
    if unknown:
        raise ValueError("Draft has unexpected attachments; refusing to overwrite them")
    # Retry the publish job using its original build artifact. Never delete an
    # uploaded file: a different rebuild belongs to a new version, not --clobber.
    existing = {asset["name"]: asset for asset in release["assets"]}
    for name, asset in existing.items():
        verify_assets(repository, tag, {"assets": [asset]}, {name: files[name]})
    missing = [str(item["path"]) for name, item in files.items() if name not in existing]
    if missing:
        gh("release", "upload", tag, "--repo", f"github.com/{repository}", *missing)
    release = find_release(repository, tag)
    verify_assets(repository, tag, release, files)
    if tag_commit(repository, tag) != source_sha:
        raise ValueError("Tag changed while preparing the release")
    gh("release", "edit", tag, "--repo", f"github.com/{repository}",
       "--verify-tag", "--draft=false", "--latest=true")
    release = find_release(repository, tag)
    if not release or release["draft"]:
        raise ValueError("GitHub did not publish the release")
    verify_assets(repository, tag, release, files)
    return {"status": "published", "url": release["html_url"], "tag": tag,
            "source_sha": source_sha}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", choices=("windows", "android"), required=True)
    parser.add_argument("--assets", type=Path)
    parser.add_argument("--metadata", type=Path)
    parser.add_argument("--draft-only", action="store_true")
    parser.add_argument("--source-sha", default=os.environ.get("GITHUB_SHA"))
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY"))
    args = parser.parse_args()
    if not args.source_sha or not SHA.fullmatch(args.source_sha):
        parser.error("A full built source commit is required")
    if not args.repository or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository):
        parser.error("An owner/repository is required")
    if os.environ.get("GITHUB_ACTIONS") == "true":
        if (os.environ.get("GITHUB_REF") != "refs/heads/main" or
                os.environ.get("GITHUB_EVENT_NAME") not in {"push", "workflow_dispatch"}):
            parser.error("Releases may only originate from a checked main build")
        if (args.source_sha != os.environ.get("GITHUB_SHA") or
                args.repository != os.environ.get("GITHUB_REPOSITORY")):
            parser.error("Release arguments must match the current workflow run")
    checked_out = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    if checked_out != args.source_sha:
        parser.error("Release source commit differs from the checked-out source")
    if args.platform == "windows":
        version = (ROOT / "WindInput/docs/VERSION").read_text().strip()
        if not VERSION.fullmatch(version) or "-" in version or not args.assets or args.draft_only:
            parser.error("Windows requires a stable version and verified release archives")
        tag, title = f"v{version}", f"困困输入法 Windows v{version}"
        files = windows_files(args.assets, version)
    else:
        if not args.metadata or not args.draft_only or args.assets:
            parser.error("Android only prepares a draft; upload the fixed-key APK after local signing")
        metadata = json.loads(args.metadata.read_text(encoding="utf-8"))
        version = metadata["version_name"]
        if metadata["git_commit"] != args.source_sha or not VERSION.fullmatch(version):
            parser.error("Android release metadata does not match this built commit")
        if (metadata.get("build_type") != "release" or
                metadata.get("debuggable") is not False or metadata.get("test_only") is not False or
                metadata.get("application_id") != "io.github.shuimowang.kunkunime" or
                metadata.get("min_sdk") != 36 or metadata.get("target_sdk") != 36):
            parser.error("Android metadata must describe the verified non-debuggable release")
        tag, title = f"android-v{version}", f"困困输入法 Android {version}"
        files = {}
    notes = ROOT / "releases" / f"{tag}.md"
    if not notes.is_file():
        parser.error(f"Missing release notes: {notes.name}")
    with tempfile.TemporaryDirectory(prefix="kunkun-release-notes-") as temp:
        complete_notes = Path(temp) / "notes.md"
        body = notes.read_text(encoding="utf-8").rstrip()
        body += f"\n\n源码提交：`{args.source_sha}`。\n"
        run = os.environ.get("GITHUB_RUN_ID")
        if run:
            body += f"\n[本次构建与检查](https://github.com/{args.repository}/actions/runs/{run})。\n"
        complete_notes.write_text(body, encoding="utf-8")
        result = publish(args.repository, tag, args.source_sha, title, complete_notes,
                         files, args.draft_only, "-" in version)
    print(json.dumps(result, ensure_ascii=False))
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(summary, "a", encoding="utf-8") as out:
            out.write(f"\n### {title}\n\n[{result['status']}]({result['url']})\n")
            if args.draft_only:
                out.write("\nAndroid 草稿等待固定私钥签名的 APK、对应源码附件及最终 SHA256SUMS；CI 临时证书 APK 不自动发布。\n")


if __name__ == "__main__":
    main()

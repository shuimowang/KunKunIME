#!/usr/bin/env python3
"""Show both stable downloads on the two current GitHub Releases."""

import argparse
import json
import os
import re
import subprocess
import sys
import time


VERSION = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
TAG = re.compile(rf"(android-v|v)({VERSION})")
HEADING = "## 下载困困输入法"


def verified_asset(release, base, filename, *, require_digest=False):
    tag = release["tag_name"]
    expected = f"{base}/releases/download/{tag}/{filename}"
    matches = [asset for asset in release["assets"] if asset["name"] == filename]
    if len(matches) != 1:
        raise ValueError(f"{tag}: expected exactly one {filename}")
    asset = matches[0]
    if (asset.get("state") != "uploaded" or not isinstance(asset.get("size"), int)
            or asset["size"] <= 0 or asset.get("browser_download_url") != expected):
        raise ValueError(f"{tag}: incomplete upload or unexpected URL for {filename}")
    digest = asset.get("digest")
    if (require_digest or digest is not None) and not re.fullmatch(r"sha256:[0-9a-fA-F]{64}", digest or ""):
        raise ValueError(f"{tag}: invalid SHA-256 for {filename}")
    return expected


def original_notes(body):
    # Only our heading and two download rows at the very start are managed.
    # Refuse an ambiguous edit instead of swallowing any of the author's notes.
    if not body.startswith(HEADING):
        return body
    prefix = re.match(
        re.escape(HEADING) + r"\r?\n\r?\n- \[Windows [^\r\n]+\r?\n- \[Android [^\r\n]+\r?\n\r?\n",
        body,
    )
    if prefix is None:
        raise ValueError("Unrecognized download section at the start of the release notes")
    return body[prefix.end():]


def plan(repository, releases):
    """Return only changed fields for publication, keyed by stable release tag."""
    if (not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository)
            or any(part in {".", ".."} for part in repository.split("/"))):
        raise ValueError(f"Invalid GitHub repository: {repository!r}")
    newest = {}
    for release in releases:
        if release.get("draft") is not False or release.get("prerelease") is not False:
            continue
        match = TAG.fullmatch(release["tag_name"])
        if match:
            platform = "Android" if match[1] == "android-v" else "Windows"
            version = tuple(map(int, match[2].split(".")))
            if platform not in newest or version > newest[platform][0]:
                newest[platform] = (version, match[2], release)
    if len(newest) != 2:
        raise ValueError("Both Windows and Android need a public stable release")

    base = f"https://github.com/{repository}"
    rows = []
    for platform in ("Windows", "Android"):
        _, version, release = newest[platform]
        tag = release["tag_name"]
        windows = platform == "Windows"
        filename = (f"KunKunIME-{version}-Windows-x64.zip" if windows
                    else f"KunKunIME-Android-{version}-arm64-v8a.apk")
        package = verified_asset(release, base, filename, require_digest=windows)
        checksum = verified_asset(release, base, "SHA256SUMS.txt")
        notes = f"{base}/blob/{tag}/releases/{tag}.md"
        kind = "x64 ZIP" if windows else "ARM64 APK"
        rows.append(f"- [{platform} {version} · {kind}]({package}) · [发行说明]({notes}) · [SHA-256]({checksum})")

    changes = {}
    for platform, other in (("Windows", "Android"), ("Android", "Windows")):
        _, version, release = newest[platform]
        title = f"困困输入法 · {platform} {version} / {other} {newest[other][1]}"
        body = release.get("body") or ""
        notes = original_notes(body)
        # GitHub and locally published notes may use either LF or CRLF.
        newline = "\r\n" if "\r\n" in body else "\n"
        # The installed Windows updater displays this section in its plain-text
        # dialog. Keep the GitHub download URLs out of its changelog.
        if platform == "Windows" and notes and "## 更新记录" not in notes and "<!-- user-facing:start -->" not in notes:
            notes = "## 更新记录" + newline * 2 + notes
        updated = newline.join([HEADING, "", *rows, "", ""]) + notes
        if title != release["name"] or updated != body:
            changes[release["tag_name"]] = {"name": title, "body": updated}
    return changes


def releases_from_github(repository):
    raw = subprocess.check_output(
        ["gh", "api", "--hostname", "github.com", "--paginate", "--slurp",
         f"repos/{repository}/releases?per_page=100"],
        encoding="utf-8",
    )
    return [release for page in json.loads(raw) for release in page]


def patch_release(repository, release_id, fields):
    subprocess.run(
        ["gh", "api", "--hostname", "github.com", "--method", "PATCH", "--silent", "--input", "-",
         f"repos/{repository}/releases/{release_id}"],
        input=json.dumps(fields, ensure_ascii=False).encode("utf-8"), check=True,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", "--repo", default=os.getenv("GITHUB_REPOSITORY", "shuimowang/KunKunIME"))
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--apply", action="store_true", help="update the two public release pages")
    mode.add_argument("--check", action="store_true", help="preview changes without writing (default)")
    parser.add_argument("--published-tag", help="wait until this newly published stable release is visible")
    args = parser.parse_args()
    if args.published_tag and not TAG.fullmatch(args.published_tag):
        parser.error("--published-tag must name a stable Windows or Android release")
    for delay in (0, 2, 4, 8):
        if delay:
            time.sleep(delay)
        releases = releases_from_github(args.repository)
        if not args.published_tag or any(
            item["tag_name"] == args.published_tag and not item["draft"] and not item["prerelease"]
            for item in releases
        ):
            break
    else:
        raise ValueError(f"New public release {args.published_tag} is not visible yet")

    changes = plan(args.repository, releases)
    if not changes:
        print("Both release pages are up to date.")
        return
    by_tag = {item["tag_name"]: item for item in releases if not item["draft"] and not item["prerelease"]}
    for tag, fields in changes.items():
        if args.apply:
            patch_release(args.repository, by_tag[tag]["id"], fields)
            print(f"Updated {tag}: {fields['name']}")
        else:
            print(f"{tag}: {fields['name']}\n{fields['body'].splitlines()[0]}\n")
            print("\n".join(fields["body"].splitlines()[2:4]), end="\n\n")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    try:
        main()
    except (ValueError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))

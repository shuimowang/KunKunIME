"""The shared download links must match the packages each updater accepts."""

import importlib.util
import io
import json
from pathlib import Path
import subprocess
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "sync_downloads", Path(__file__).resolve().parents[2] / "scripts/sync-release-downloads.py",
)
sync = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sync)

REPO = "owner/project"
RELEASES_URL = f"https://github.com/{REPO}/releases"


def release(tag, *, draft=False, prerelease=False):
    android = tag.startswith("android-v")
    version = tag.removeprefix("android-v" if android else "v")
    installer = (f"KunKunIME-Android-{version}-arm64-v8a.apk" if android
                 else f"KunKunIME-{version}-Windows-x64.zip")
    assets = [
        {
            "name": name, "state": "uploaded", "size": 258,
            "digest": "sha256:" + "a" * 64,
            "browser_download_url": f"{RELEASES_URL}/download/{tag}/{name}",
        }
        for name in (installer, "SHA256SUMS.txt")
    ]
    return {
        "tag_name": tag, "name": f"困困输入法 {tag}", "html_url": f"{RELEASES_URL}/tag/{tag}",
        "draft": draft, "prerelease": prerelease, "assets": assets,
        "body": f"## {tag}\r\n\r\n原有的更新说明。\r\n\r\n[构建记录](https://example.org/tests)\r\n",
    }


class SharedDownloadsTest(unittest.TestCase):
    def test_both_pages_link_to_latest_stable_packages_in_numeric_order(self):
        windows = release("v0.1.13")
        android = release("android-v0.1.10")
        others = [release("v0.1.9"), release("android-v0.1.9"), release("v9.0.0", draft=True),
                  release("android-v8.0.0", prerelease=True), release("android-v7.0.0-rc.1")]
        changes = sync.plan(REPO, [*others, android, windows])
        self.assertEqual(set(changes), {windows["tag_name"], android["tag_name"]})
        for page in (windows, android):
            with self.subTest(tag=page["tag_name"]):
                update = changes[page["tag_name"]]
                self.assertEqual(set(update), {"name", "body"})
                self.assertIn("Windows 0.1.13", update["name"])
                self.assertIn("Android 0.1.10", update["name"])
                self.assertTrue(update["body"].endswith(page["body"]))
                for selected in (windows, android):
                    self.assertIn(selected["assets"][0]["browser_download_url"], update["body"])
                    self.assertIn(selected["assets"][1]["browser_download_url"], update["body"])
                for unused in others:
                    self.assertNotIn(unused["assets"][0]["browser_download_url"], update["body"])

    def test_sync_is_idempotent_and_refreshes_current_pages_after_android_release(self):
        windows, old_android = release("v0.1.13"), release("android-v0.1.2")
        original_windows_notes = windows["body"]
        releases = [windows, old_android]
        first = sync.plan(REPO, releases)
        for page in releases:
            page.update(first[page["tag_name"]])
        self.assertEqual({}, sync.plan(REPO, releases))

        android = release("android-v0.2.0")
        releases.append(android)
        second = sync.plan(REPO, releases)
        self.assertEqual(set(second), {windows["tag_name"], android["tag_name"]})
        revised = second[windows["tag_name"]]["body"]
        self.assertTrue(revised.endswith(original_windows_notes))
        self.assertEqual(1, revised.count(windows["assets"][0]["browser_download_url"]))
        self.assertNotIn(old_android["assets"][0]["browser_download_url"], revised)
        self.assertIn(android["assets"][0]["browser_download_url"], revised)

    def test_invalid_assets_on_newest_release_never_fall_back_to_an_older_link(self):
        for index, field, value in (
            (0, "browser_download_url",
             f"{RELEASES_URL}/download/android-v0.1.1/KunKunIME-Android-0.1.2-arm64-v8a.apk"),
            (1, "browser_download_url", "https://elsewhere.example/SHA256SUMS.txt"),
            (0, "state", "starter"), (0, "size", 0), (1, "name", "missing.txt"),
        ):
            with self.subTest(asset=index, field=field):
                newest = release("android-v0.1.2")
                newest["assets"][index][field] = value
                with self.assertRaises(ValueError):
                    sync.plan(REPO, [release("v0.1.13"), release("android-v0.1.1"), newest])

    def test_preview_writes_nothing_and_apply_patches_only_name_and_body(self):
        windows, android = release("v0.1.13"), release("android-v0.1.2")
        windows["id"], android["id"] = 11, 12
        for options in ([], ["--apply"]):
            with (
                self.subTest(options=options),
                redirect_stdout(io.StringIO()),
                patch("sys.argv", ["sync-release-downloads.py", "--repository", REPO, *options]),
                patch.object(sync, "releases_from_github", return_value=[windows, android]),
                patch.object(subprocess, "run", return_value=subprocess.CompletedProcess(
                    args=[], returncode=0, stdout="{}", stderr="",
                )) as run,
            ):
                sync.main()
                self.assertEqual(2 if options else 0, run.call_count)
                for invocation in run.call_args_list:
                    args = invocation.args[0]
                    self.assertEqual(["gh", "api"], args[:2])
                    self.assertIn("PATCH", args)
                    self.assertTrue(any(f"repos/{REPO}/releases/{i}" in args for i in (11, 12)))
                    fields = json.loads(invocation.kwargs["input"])
                    self.assertEqual({"name", "body"}, set(fields))

    def test_just_published_android_release_is_visible_before_planning_links(self):
        windows, previous = release("v0.1.13"), release("android-v0.1.1")
        android = release("android-v0.1.2")
        windows["id"], android["id"] = 11, 12
        with (
            redirect_stdout(io.StringIO()),
            patch("sys.argv", ["sync-release-downloads.py", "--repository", REPO,
                               "--apply", "--published-tag", android["tag_name"]]),
            patch.object(sync, "releases_from_github", side_effect=[
                [windows, previous], [windows, previous, android],
            ]) as reads,
            patch.object(sync.time, "sleep"),
            patch.object(sync, "patch_release") as writes,
        ):
            sync.main()
        self.assertEqual(2, reads.call_count)
        self.assertEqual({11, 12}, {call.args[1] for call in writes.call_args_list})
        for call in writes.call_args_list:
            self.assertIn(android["assets"][0]["browser_download_url"], call.args[2]["body"])


if __name__ == "__main__":
    unittest.main()

"""Checks for publication mistakes that could expose an incomplete/wrong build."""

import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest.mock import call, patch


SPEC = importlib.util.spec_from_file_location(
    "publisher", Path(__file__).resolve().parents[2] / "scripts/publish-release.py",
)
publisher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publisher)

COMMIT = "a" * 40
OTHER = "b" * 40
REPO = "owner/project"
TAG = "v0.1.10"


class ReleasePublicationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        lines = []
        for label in ("Windows-x64", "Cargo-sources"):
            name = f"KunKunIME-0.1.10-{label}.zip"
            payload = ("release contents " + label).encode()
            (self.directory / name).write_bytes(payload)
            lines.append(hashlib.sha256(payload).hexdigest() + "  " + name)
        (self.directory / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n")
        self.files = publisher.windows_files(self.directory, "0.1.10")
        self.notes = self.directory / "notes.md"
        self.notes.write_text("release notes")

    def release(self, *, draft=True, names=None):
        return {
            "draft": draft, "target_commitish": COMMIT,
            "tag_name": TAG, "html_url": "https://github.com/owner/project/releases/tag/v0.1.10",
            "assets": [dict(name=name, state="uploaded", size=item["size"], digest=item["digest"])
                       for name, item in self.files.items() if names is None or name in names],
        }

    def publish(self):
        return publisher.publish(REPO, TAG, COMMIT, "release", self.notes,
                                 self.files, False, False)

    def test_corrupt_archive_fails_checksum_before_publication(self):
        (self.directory / "KunKunIME-0.1.10-Windows-x64.zip").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "Checksum mismatch"):
            publisher.windows_files(self.directory, "0.1.10")

    def test_checksum_cannot_name_a_file_outside_release_directory(self):
        (self.directory / "SHA256SUMS.txt").write_text("0" * 64 + "  ../private-key.p12\n")
        with self.assertRaisesRegex(ValueError, "Unexpected"):
            publisher.windows_files(self.directory, "0.1.10")

    def test_another_commits_tag_cannot_be_moved_or_published(self):
        with patch.object(publisher, "tag_commit", return_value=OTHER), \
                patch.object(publisher, "api") as api, patch.object(publisher, "gh") as gh:
            with self.assertRaisesRegex(ValueError, "another commit"):
                self.publish()
            api.assert_not_called()
            gh.assert_not_called()

    def test_retry_uploads_only_missing_files_in_same_commit_draft(self):
        existing_name = next(iter(self.files))
        with patch.object(publisher, "ensure_tag"), \
                patch.object(publisher, "tag_commit", return_value=COMMIT), \
                patch.object(publisher, "find_release", side_effect=[
                    self.release(names={existing_name}), self.release(), self.release(draft=False),
                ]), patch.object(publisher, "gh") as gh:
            self.assertEqual("published", self.publish()["status"])
            upload = [c.args for c in gh.call_args_list if c.args[:2] == ("release", "upload")]
            self.assertEqual(1, len(upload))
            self.assertNotIn(str(self.files[existing_name]["path"]), upload[0])
            self.assertNotIn("--clobber", upload[0])
            for name, item in self.files.items():
                if name != existing_name:
                    self.assertIn(str(item["path"]), upload[0])

    def test_failed_remote_digest_check_leaves_release_unpublished(self):
        damaged = self.release()
        damaged["assets"][0]["digest"] = "sha256:" + "0" * 64
        with patch.object(publisher, "ensure_tag"), \
                patch.object(publisher, "find_release", side_effect=[self.release(names=set()), damaged]), \
                patch.object(publisher, "gh") as gh:
            with self.assertRaisesRegex(ValueError, "digest mismatch"):
                self.publish()
            self.assertFalse(any(c.args[:2] == ("release", "edit") for c in gh.call_args_list))

    def test_public_release_is_checked_without_replacing_assets(self):
        with patch.object(publisher, "ensure_tag"), \
                patch.object(publisher, "find_release", return_value=self.release(draft=False)), \
                patch.object(publisher, "gh") as gh:
            self.assertEqual("already_published", self.publish()["status"])
            gh.assert_not_called()

    def test_public_release_with_different_bytes_is_not_claimed_as_this_build(self):
        different = self.release(draft=False)
        different["assets"][0]["digest"] = "sha256:" + "f" * 64
        with patch.object(publisher, "ensure_tag"), \
                patch.object(publisher, "find_release", return_value=different), \
                patch.object(publisher, "gh") as gh:
            with self.assertRaisesRegex(ValueError, "digest mismatch"):
                self.publish()
            gh.assert_not_called()

    def test_android_draft_never_uploads_or_publishes_ci_signed_apk(self):
        with patch.object(publisher, "ensure_tag"), \
                patch.object(publisher, "find_release", return_value=self.release()), \
                patch.object(publisher, "gh") as gh:
            result = publisher.publish(REPO, "android-v0.1.0-preview.4", COMMIT,
                                       "android", self.notes, {}, True, True)
            self.assertEqual("awaiting_signed_android_apk", result["status"])
            gh.assert_not_called()

    def test_created_draft_waits_only_until_release_list_becomes_visible(self):
        tag = "android-v0.1.0"
        release = dict(self.release(names=set()), tag_name=tag)
        for missed_reads in range(5):
            with self.subTest(missed_reads=missed_reads), \
                    patch.object(publisher, "ensure_tag") as ensure_tag, \
                    patch.object(publisher, "api", side_effect=[
                        [], *([[]] * missed_reads), [release],
                    ]) as api, patch.object(publisher, "gh") as gh, \
                    patch.object(publisher.time, "sleep") as sleep:
                result = publisher.publish(REPO, tag, COMMIT, "android", self.notes,
                                           {}, True, False)
                self.assertEqual("awaiting_signed_android_apk", result["status"])
                self.assertEqual(COMMIT, result["source_sha"])
                ensure_tag.assert_called_once_with(REPO, tag, COMMIT)
                self.assertEqual([call(delay) for delay in (1, 2, 4, 8)[:missed_reads]],
                                 sleep.call_args_list)
                self.assertEqual([call(f"repos/{REPO}/releases?per_page=100&page=1")] *
                                 (missed_reads + 2), api.call_args_list)
                gh.assert_called_once()
                self.assertEqual(("release", "create", tag), gh.call_args.args[:3])
                self.assertIn("--draft", gh.call_args.args)
                self.assertIn("--latest=false", gh.call_args.args)

    def test_created_draft_permanently_missing_stops_without_creating_again(self):
        with patch.object(publisher, "ensure_tag") as ensure_tag, \
                patch.object(publisher, "api", return_value=[]) as api, \
                patch.object(publisher, "gh") as gh, \
                patch.object(publisher.time, "sleep") as sleep:
            with self.assertRaisesRegex(ValueError, "Expected an unpublished draft"):
                self.publish()
            ensure_tag.assert_called_once_with(REPO, TAG, COMMIT)
            self.assertEqual(6, api.call_count)
            self.assertEqual([call(1), call(2), call(4), call(8)], sleep.call_args_list)
            gh.assert_called_once()
            self.assertEqual(("release", "create", TAG), gh.call_args.args[:3])

    def test_created_release_must_still_be_an_unpublished_same_commit_draft(self):
        for visible, message in (
            (self.release(draft=False), "Expected an unpublished draft"),
            (dict(self.release(), target_commitish=OTHER), "different commit"),
        ):
            with self.subTest(message=message), patch.object(publisher, "ensure_tag"), \
                    patch.object(publisher, "api", side_effect=[[], [], [visible]]) as api, \
                    patch.object(publisher, "gh") as gh, \
                    patch.object(publisher.time, "sleep") as sleep:
                with self.assertRaisesRegex(ValueError, message):
                    self.publish()
                self.assertEqual(3, api.call_count)
                sleep.assert_called_once_with(1)
                gh.assert_called_once()
                self.assertEqual(("release", "create", TAG), gh.call_args.args[:3])

    def test_created_draft_lookup_errors_are_not_retried(self):
        with patch.object(publisher, "ensure_tag"), \
                patch.object(publisher, "api", side_effect=[[], RuntimeError("HTTP 403")]) as api, \
                patch.object(publisher, "gh") as gh, \
                patch.object(publisher.time, "sleep") as sleep:
            with self.assertRaisesRegex(RuntimeError, "HTTP 403"):
                self.publish()
            self.assertEqual(2, api.call_count)
            sleep.assert_not_called()
            gh.assert_called_once()
            self.assertEqual(("release", "create", TAG), gh.call_args.args[:3])

    def test_android_version_selects_channel_without_publishing_or_replacing_latest(self):
        for version, prerelease in (("0.1.0", False), ("0.1.0-preview.4", True)):
            with self.subTest(version=version):
                tag = f"android-v{version}"
                notes_dir = self.directory / "releases"
                notes_dir.mkdir(exist_ok=True)
                (notes_dir / f"{tag}.md").write_text("Android release notes")
                metadata = self.directory / "release-info.json"
                metadata.write_text(json.dumps({
                    "version_name": version, "version_code": 5,
                    "git_commit": COMMIT, "build_type": "release",
                    "debuggable": False, "test_only": False,
                    "application_id": "io.github.shuimowang.kunkunime",
                    "min_sdk": 36, "target_sdk": 36,
                }))
                release = dict(self.release(names=set()), tag_name=tag, prerelease=prerelease)
                arguments = ["publish-release.py", "--platform", "android", "--metadata", str(metadata),
                             "--draft-only", "--source-sha", COMMIT, "--repository", REPO]
                with patch.object(publisher, "ROOT", self.directory), \
                        patch("sys.argv", arguments), patch.dict(os.environ, {}, clear=True), \
                        patch.object(publisher.subprocess, "check_output", return_value=COMMIT), \
                        patch.object(publisher, "ensure_tag"), \
                        patch.object(publisher, "find_release", side_effect=[None, release]), \
                        patch.object(publisher, "gh") as gh, redirect_stdout(io.StringIO()) as output:
                    publisher.main()
                self.assertEqual("awaiting_signed_android_apk", json.loads(output.getvalue())["status"])
                gh.assert_called_once()
                command = gh.call_args.args
                self.assertEqual(("release", "create", tag), command[:3])
                self.assertEqual(prerelease, "--prerelease" in command)
                self.assertIn("--draft", command)
                self.assertIn("--latest=false", command)
                self.assertNotIn("--latest=true", command)


if __name__ == "__main__":
    unittest.main()

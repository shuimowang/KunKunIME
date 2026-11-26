"""Checks for publication mistakes that could expose an incomplete/wrong build."""

import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


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


if __name__ == "__main__":
    unittest.main()

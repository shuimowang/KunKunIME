import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).resolve().parents[2] / "scripts/package-windows-files.py"
spec = importlib.util.spec_from_file_location("windows_bundle", SCRIPT)
packager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packager)


class WindowsBundleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.stage = self.root / "stage"
        self.stage.mkdir()
        for name in packager.ROOT_FILES:
            (self.stage / name).write_bytes(name.encode())
        for name in packager.ROOT_DIRS:
            (self.stage / name).mkdir()
            (self.stage / name / "built.txt").write_bytes(b"factory")

    def test_archive_and_manifest_only_contain_this_build(self):
        output = self.root / "existing"
        (output / "data").mkdir(parents=True)
        (output / "data/private.txt").write_bytes(b"personal")
        (output / "portable_mode").touch()
        archive = self.root / "package.zip"
        packager.bundle(self.stage, output, archive)
        inventory = json.loads((output / packager.MANIFEST).read_text(encoding="utf-8"))
        managed = {entry["path"]: entry["sha256"] for entry in inventory["files"]}
        self.assertEqual(inventory["product"], "KunKunIME")
        self.assertNotIn(packager.MANIFEST, managed)
        self.assertNotIn("data/private.txt", managed)
        self.assertNotIn("portable_mode", managed)
        self.assertEqual((output / "data/private.txt").read_bytes(), b"personal")
        with zipfile.ZipFile(archive) as package:
            self.assertEqual(set(package.namelist()), {"困困输入法/" + n for n in [*managed, packager.MANIFEST]})
            for name, digest in managed.items():
                self.assertEqual(hashlib.sha256(package.read("困困输入法/" + name)).hexdigest(), digest)

    def test_portable_installation_cannot_be_used_as_clean_staging(self):
        (self.stage / "userdata").mkdir()
        with self.assertRaises(ValueError):
            packager.bundle(self.stage)
        self.assertFalse((self.stage / packager.MANIFEST).exists())

    def test_existing_output_link_is_rejected_before_copying(self):
        output = self.root / "existing"
        output.mkdir()
        victim = self.root / "untouched"
        victim.mkdir()
        if os.name == "nt":
            subprocess.run([
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "$ErrorActionPreference='Stop'; New-Item -ItemType Junction "
                "-Path $env:KUNKUN_TEST_LINK -Target $env:KUNKUN_TEST_TARGET | Out-Null",
            ], env={**os.environ, "KUNKUN_TEST_LINK": str(output / "data"),
                    "KUNKUN_TEST_TARGET": str(victim)}, check=True)
        else:
            (output / "data").symlink_to(victim, target_is_directory=True)
        with self.assertRaises(ValueError):
            packager.bundle(self.stage, output)
        self.assertEqual(list(victim.iterdir()), [])
        self.assertFalse((output / "KunKunIME.exe").exists())

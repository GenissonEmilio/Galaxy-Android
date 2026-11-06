"""Regression checks for manifest-driven icon validation after AGP shrinking."""
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
import zipfile

import verify_android_apk as verifier


class ManifestResourcesTests(unittest.TestCase):
    manifest = """E: application
      A: android:icon(0x01010002)=@0x7f080000
      A: android:roundIcon(0x0101052c)=@0x7f080000
"""
    resources = """resource 0x7f080000 app:mipmap/ic_launcher: t=0x03 d=0x00000000
  (string8) "res/E4.xml"
resource 0x7f040001 app:drawable/foreground: t=0x03 d=0x00000001
  (string8) "res/ab.png"
"""

    def verify(self, names=("res/E4.xml", "res/ab.png"), manifest=None, resources=None):
        def dump(_aapt, *args):
            if args[0] == "--values":
                return self.resources if resources is None else resources
            if args[-1] == "AndroidManifest.xml":
                return self.manifest if manifest is None else manifest
            return "E: adaptive-icon\n A: android:drawable(0x01010199)=@0x7f040001\n"

        with tempfile.TemporaryDirectory() as directory:
            apk_path = Path(directory) / "fixture.apk"
            with zipfile.ZipFile(apk_path, "w") as apk:
                for name in names:
                    apk.writestr(name, b"synthetic")
            with zipfile.ZipFile(apk_path) as apk, patch.object(verifier, "aapt_dump", dump):
                return verifier.verify_manifest_resources(apk, Path("aapt"))

    def test_shrunk_icon_and_adaptive_child_are_resolved(self):
        self.assertEqual(self.verify(), ["res/E4.xml", "res/ab.png"])

    def test_missing_referenced_child_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, "Missing icon resource file: res/ab.png"):
            self.verify(names=("res/E4.xml",))

    def test_missing_manifest_icon_id_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, "Missing icon resource ID"):
            self.verify(resources="")

    def test_debuggable_attribute_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, "debuggable"):
            self.verify(manifest=self.manifest + " A: android:debuggable(0x0101000f)=(type 0x12)0xffffffff\n")

    def test_absent_launcher_icon_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, "no launcher icon"):
            self.verify(manifest="E: application\n")


if __name__ == "__main__":
    unittest.main()

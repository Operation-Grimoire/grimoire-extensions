import copy
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from build_dual import validate
from compare_contracts import compare


class ArtifactGatesTest(unittest.TestCase):
    def test_jar_rejects_api_bundling_and_incompatible_metadata_before_android_tools(self):
        expected = {"package": "io.grimoire.extension.fixture", "sourceClass": "io.grimoire.extension.fixture.Fixture",
                    "versionCode": "1", "versionName": "1.0.0", "sourceVersionCode": "2"}
        headers = {"Grimoire-Extension-Format": "1", "Grimoire-Core-Api": "1.4",
                   "Grimoire-Source-Package": expected["package"], "Grimoire-Source-Class": expected["sourceClass"],
                   "Grimoire-Version-Code": "1", "Grimoire-Version-Name": "1.0.0", "Grimoire-Source-Version-Code": "2"}
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "fixture.jar"
            for change in ("bundled-api", "bad-api", "bad-version"):
                with self.subTest(change=change):
                    values = dict(headers)
                    if change == "bad-api":
                        values["Grimoire-Core-Api"] = "9.0"
                    if change == "bad-version":
                        values["Grimoire-Version-Code"] = "99"
                    with zipfile.ZipFile(jar, "w") as archive:
                        archive.writestr("META-INF/MANIFEST.MF", "\r\n".join(f"{k}: {v}" for k, v in values.items()) + "\r\n\r\n")
                        archive.writestr(expected["sourceClass"].replace(".", "/") + ".class", bytes.fromhex("cafebabe00000037"))
                        if change == "bundled-api":
                            archive.writestr("io/grimoire/api/source/Source.class", b"duplicate")
                    with patch("build_dual.subprocess.check_output") as android_tools:
                        with self.assertRaises(ValueError):
                            validate(jar, Path("fixture.apk"), Path("aapt2"), expected)
                        android_tools.assert_not_called()

    def test_native_comparison_rejects_changed_content_stale_apk_and_wrong_identity(self):
        jvm = {"sourceId": "123", "sourceClass": "fixture.Source", "sourceVersionCode": "2", "pages": "text"}
        native = {"contract": jvm, "apkSha256": "hash", "apkPackage": "fixture", "apkVersionCode": 1, "apkVersionName": "1.0.0"}
        artifacts = {"sourceId": "123", "identity": {"package": "fixture", "versionCode": "1", "versionName": "1.0.0",
                     "sourceClass": "fixture.Source", "sourceVersionCode": "2"}, "artifacts": {"android": {"sha256": "hash"}, "jvm": {"sha256": "jarhash"}}}
        jvm_report = {"contract": jvm, "jarSha256": "jarhash"}
        compare(native, jvm_report, artifacts)
        for field in ("pages", "apkSha256", "apkPackage", "apkVersionCode", "apkVersionName"):
            changed = copy.deepcopy(native)
            if field == "pages":
                changed["contract"][field] = "other content"
            else:
                changed[field] = "different"
            with self.subTest(field=field), self.assertRaises(ValueError):
                compare(changed, jvm_report, artifacts)
        with self.assertRaises(ValueError):
            compare(native, {"contract": jvm, "jarSha256": "stale"}, artifacts)


if __name__ == "__main__":
    unittest.main()

import copy
import json
import subprocess
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from build_dual import expected_identity, extension_dirs, source_identity, validate, validate_jar
from compare_contracts import compare


class ArtifactGatesTest(unittest.TestCase):
    def test_pass_output_requires_runtime_level_and_complete_hash_evidence(self):
        contract = {"sourceId": "123", "sourceClass": "fixture.Source", "sourceVersionCode": "2", "pagesSha256": "pageshash"}
        native = {"contract": contract, "apkSha256": "apkhash", "apkPackage": "fixture", "apkVersionCode": 1,
                  "apkVersionName": "1.0.0", "apkCoreApi": "1.5", "androidApiLevel": 35}
        jvm = {"contract": contract, "jarSha256": "jarhash"}
        artifacts = {"sourceId": "123", "jvmCoreApi": "1.5", "identity": {"package": "fixture", "versionCode": "1",
                     "versionName": "1.0.0", "sourceClass": "fixture.Source", "sourceVersionCode": "2"},
                     "artifacts": {"android": {"sha256": "apkhash"}, "jvm": {"sha256": "jarhash"}}}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for index, level in enumerate((35, None, 0, -1, "35", True, "missing-pages")):
                candidate = copy.deepcopy(native)
                candidate["androidApiLevel"] = 35 if level == "missing-pages" else level
                jvm_candidate = copy.deepcopy(jvm)
                if level == "missing-pages":
                    del candidate["contract"]["pagesSha256"]
                    del jvm_candidate["contract"]["pagesSha256"]
                paths = []
                for name, value in (("native", candidate), ("jvm", jvm_candidate), ("artifacts", artifacts)):
                    path = root / f"{name}.json"
                    path.write_text(json.dumps(value), encoding="utf-8")
                    paths.append(str(path))
                output = root / f"result-{index}.json"
                result = subprocess.run([sys.executable, str(Path(__file__).resolve().parents[1] / "compare_contracts.py"),
                                         "--android", paths[0], "--jvm", paths[1], "--artifacts", paths[2],
                                         "--output", str(output)], capture_output=True, text=True)
                with self.subTest(level=level):
                    if index == 0:
                        self.assertEqual(result.returncode, 0, result.stderr)
                        report = json.loads(output.read_text(encoding="utf-8"))
                        self.assertEqual(report["status"], "PASS")
                        self.assertEqual(report["androidApiLevel"], 35)
                        self.assertEqual(report["pagesSha256"], "pageshash")
                    else:
                        self.assertNotEqual(result.returncode, 0)
                        self.assertFalse(output.exists())

    def test_apk_requirement_must_exist_and_match_the_jar(self):
        expected = {"package": "io.grimoire.extension.fixture", "sourceClass": "io.grimoire.extension.fixture.Fixture",
                    "versionCode": "1", "versionName": "1.0.0", "sourceVersionCode": "2"}
        headers = {"Grimoire-Extension-Format": "1", "Grimoire-Core-Api": "1.5",
                   "Grimoire-Source-Package": expected["package"], "Grimoire-Source-Class": expected["sourceClass"],
                   "Grimoire-Version-Code": "1", "Grimoire-Version-Name": "1.0.0", "Grimoire-Source-Version-Code": "2"}
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "fixture.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("META-INF/MANIFEST.MF", "\r\n".join(f"{k}: {v}" for k, v in headers.items()) + "\r\n\r\n")
                archive.writestr(expected["sourceClass"].replace(".", "/") + ".class", bytes.fromhex("cafebabe00000037"))
            badging = "package: name='io.grimoire.extension.fixture' versionCode='1' versionName='1.0.0'"
            entry = 'A: android:name="grimoire.extension.class"\n  A: android:value="io.grimoire.extension.fixture.Fixture"\n'
            requirement = 'A: android:name="grimoire.extension.minApi"\n  A: android:value=@0x7f090000\n'
            for tree, resource in ((entry, "1.5"), (entry + requirement, "1.4"), (entry + requirement, "1.5")):
                resources = f'resource 0x7f090000 string/grimoire_extension_min_api\n  () "{resource}"\n'
                with self.subTest(declared=bool(requirement in tree), api=resource), patch(
                    "build_dual.subprocess.check_output", side_effect=[badging, tree, resources],
                ):
                    if tree == entry + requirement and resource == "1.5":
                        self.assertEqual(validate(jar, Path("fixture.apk"), Path("aapt2"), expected)["Grimoire-Core-Api"], "1.5")
                    else:
                        with self.assertRaises(ValueError):
                            validate(jar, Path("fixture.apk"), Path("aapt2"), expected)

    def test_jar_core_requirement_follows_the_shared_api_pin(self):
        expected = {"package": "io.grimoire.extension.fixture", "sourceClass": "io.grimoire.extension.fixture.Fixture",
                    "versionCode": "1", "versionName": "1.0.0", "sourceVersionCode": "2"}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "gradle").mkdir()
            catalog = root / "gradle/libs.versions.toml"
            jar = root / "fixture.jar"
            for pin in ("1.5.0", "1.5.1-SNAPSHOT"):
                catalog.write_text(f'[versions]\nextensionsApi = "{pin}"\n', encoding="utf-8")
                for requirement in ("1.4", "1.5", "9.0"):
                    headers = {"Grimoire-Extension-Format": "1", "Grimoire-Core-Api": requirement,
                               "Grimoire-Source-Package": expected["package"], "Grimoire-Source-Class": expected["sourceClass"],
                               "Grimoire-Version-Code": "1", "Grimoire-Version-Name": "1.0.0", "Grimoire-Source-Version-Code": "2"}
                    with zipfile.ZipFile(jar, "w") as archive:
                        archive.writestr("META-INF/MANIFEST.MF", "\r\n".join(f"{k}: {v}" for k, v in headers.items()) + "\r\n\r\n")
                        archive.writestr(expected["sourceClass"].replace(".", "/") + ".class", bytes.fromhex("cafebabe00000037"))
                    with self.subTest(pin=pin, requirement=requirement), patch("build_dual.ROOT", root):
                        if requirement == "1.5":
                            self.assertEqual(validate_jar(jar, expected)["Grimoire-Core-Api"], "1.5")
                        else:
                            with self.assertRaises(ValueError):
                                validate_jar(jar, expected)

    def test_jar_rejects_api_bundling_and_incompatible_metadata_before_android_tools(self):
        expected = {"package": "io.grimoire.extension.fixture", "sourceClass": "io.grimoire.extension.fixture.Fixture",
                    "versionCode": "1", "versionName": "1.0.0", "sourceVersionCode": "2"}
        headers = {"Grimoire-Extension-Format": "1", "Grimoire-Core-Api": "1.5",
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
        native = {"contract": jvm, "apkSha256": "hash", "apkPackage": "fixture", "apkVersionCode": 1, "apkVersionName": "1.0.0", "apkCoreApi": "1.5", "androidApiLevel": 35}
        artifacts = {"sourceId": "123", "identity": {"package": "fixture", "versionCode": "1", "versionName": "1.0.0",
                     "sourceClass": "fixture.Source", "sourceVersionCode": "2"}, "artifacts": {"android": {"sha256": "hash"}, "jvm": {"sha256": "jarhash"}}}
        artifacts["jvmCoreApi"] = "1.5"
        jvm_report = {"contract": jvm, "jarSha256": "jarhash"}
        compare(native, jvm_report, artifacts)
        for field in ("pages", "apkSha256", "apkPackage", "apkVersionCode", "apkVersionName", "apkCoreApi"):
            changed = copy.deepcopy(native)
            if field == "pages":
                changed["contract"][field] = "other content"
            else:
                changed[field] = "different"
            with self.subTest(field=field), self.assertRaises(ValueError):
                compare(changed, jvm_report, artifacts)
        with self.assertRaises(ValueError):
            compare(native, {"contract": jvm, "jarSha256": "stale"}, artifacts)


class SourceIdentityTest(unittest.TestCase):
    def _write_extension(self, root: Path, *, version_name: str = "1.0.3", source_version: str = "5", one_line: bool = False) -> None:
        (root / "build.gradle.kts").write_text(
            'applicationId = "io.grimoire.extension.fixture"\n'
            "versionCode = 4\n"
            f'versionName = "{version_name}"\n',
            encoding="utf-8")
        main = root / "src" / "main"
        main.mkdir(parents=True)
        (main / "AndroidManifest.xml").write_text(
            '<meta-data\n'
            '    android:name="grimoire.extension.class"\n'
            '    android:value="io.grimoire.extension.fixture.Fixture" />\n',
            encoding="utf-8")
        java = main / "java"
        java.mkdir()
        if one_line:
            source = f'@SourceInfo(name = "Fixture", versionCode = {source_version})\nclass Fixture\n'
        else:
            source = f'@SourceInfo(\n    name = "Fixture",\n    versionCode = {source_version},\n)\nclass Fixture\n'
        (java / "Fixture.kt").write_text(source, encoding="utf-8")

    def test_source_identity_keeps_artifact_version_separate_from_annotation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_extension(root)
            self.assertEqual(source_identity(root), {
                "package": "io.grimoire.extension.fixture",
                "sourceClass": "io.grimoire.extension.fixture.Fixture",
                "versionCode": "4",
                "versionName": "1.0.3",
                "sourceVersionCode": "5",
            })

    def test_one_line_source_info_still_supplies_the_annotation_version(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_extension(root, source_version="12", one_line=True)
            self.assertEqual(source_identity(root)["sourceVersionCode"], "12")

    def test_stale_identity_json_fails_after_the_android_declaration_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_extension(root, version_name="1.0.4", source_version="9")
            stale = {"package": "io.grimoire.extension.fixture",
                     "sourceClass": "io.grimoire.extension.fixture.Fixture",
                     "versionCode": "4", "versionName": "1.0.3", "sourceVersionCode": "5"}
            with self.assertRaisesRegex(ValueError, "current Android declarations"):
                expected_identity(root, stale)
            current = dict(stale, versionName="1.0.4", sourceVersionCode="9")
            self.assertEqual(expected_identity(root, current)["versionName"], "1.0.4")

    def test_royal_road_identity_comes_from_its_android_module(self):
        identity = source_identity(Path(__file__).resolve().parents[2] / "src" / "en" / "royalroad")
        self.assertEqual(identity["package"], "io.grimoire.extension.en.royalroad")
        self.assertEqual(identity["sourceClass"], "io.grimoire.extension.en.royalroad.RoyalRoad")
        self.assertEqual(identity["versionCode"], "5")
        self.assertEqual(identity["versionName"], "1.0.4")
        self.assertEqual(identity["sourceVersionCode"], "6")

    def test_every_extension_declares_a_readable_identity(self):
        directories = extension_dirs()
        self.assertGreaterEqual(len(directories), 26)
        for directory in directories:
            identity = source_identity(directory)
            with self.subTest(module=f"{directory.parent.name}-{directory.name}"):
                self.assertTrue(identity["sourceClass"].startswith(identity["package"]))
                self.assertTrue(identity["versionCode"].isdigit())
                self.assertTrue(identity["sourceVersionCode"].isdigit())
                self.assertTrue(identity["versionName"])


if __name__ == "__main__":
    unittest.main()

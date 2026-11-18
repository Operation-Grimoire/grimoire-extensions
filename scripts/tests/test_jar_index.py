import base64
import contextlib
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import jar_index  # noqa: E402

LIB = "io.grimoire:extensions-lib:1.0.0-SNAPSHOT"
JSON_LIB = "org.json:json:20240303"
HAS_OPENSSL = shutil.which("openssl") is not None


def write_extension(root: Path, lang: str, name: str, version_code: int = 3, *, uses_lib: bool = False,
                    adult: str | None = None, identity_overrides: dict | None = None) -> dict:
    package = f"io.grimoire.extension.{lang}.{name}"
    source_class = f"{package}.Source"
    version_name = f"1.0.{version_code - 1}"
    directory = root / "src" / lang / name
    code = directory / "src/main/java" / package.replace(".", "/")
    code.mkdir(parents=True)
    (directory / "build.gradle.kts").write_text("// fixture\n", encoding="utf-8")
    adult_line = f"    adultContent = AdultContent.{adult},\n" if adult else ""
    (code / "Source.kt").write_text(
        f"package {package}\n\n@SourceInfo(\n    name = \"{name.title()} Novels\",\n    lang = Language.EN,\n"
        f"{adult_line}    versionCode = 2,\n)\nclass Source\n", encoding="utf-8")
    libraries = ",".join([JSON_LIB] + ([LIB] if uses_lib else []))
    identity = {"module": f"{lang}-{name}", "package": package, "sourceClass": source_class,
                "versionCode": str(version_code), "versionName": version_name, "sourceVersionCode": "2",
                "coreApi": "1.6", "hostLibraries": libraries}
    headers = {"Manifest-Version": "1.0", "Grimoire-Extension-Format": "1", "Grimoire-Core-Api": "1.6",
               "Grimoire-Source-Package": package, "Grimoire-Source-Class": source_class,
               "Grimoire-Version-Code": str(version_code), "Grimoire-Version-Name": version_name,
               "Grimoire-Source-Version-Code": "2", "Grimoire-Host-Libraries": libraries}
    output = root / "jvm/build/extensions" / identity["module"]
    output.mkdir(parents=True)
    jar = output / f"{package}-{version_name}.jar"
    with zipfile.ZipFile(jar, "w") as archive:
        archive.writestr("META-INF/MANIFEST.MF", "\r\n".join(f"{k}: {v}" for k, v in headers.items()) + "\r\n\r\n")
        archive.writestr(source_class.replace(".", "/") + ".class", bytes.fromhex("cafebabe00000037"))
    (output / "identity.json").write_text(json.dumps({**identity, **(identity_overrides or {})}), encoding="utf-8")
    return {"jar": jar, "package": package, "headers": headers}


def write_library(root: Path) -> Path:
    host = root / "jvm/build/host"
    host.mkdir(parents=True, exist_ok=True)
    path = host / "extensions-lib-1.0.0-SNAPSHOT.jar"
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("io/grimoire/extensions/lib/Base.class", b"lib")
    return path


def quiet(function, *args, **kwargs):
    with contextlib.redirect_stdout(io.StringIO()) as output:
        return function(*args, **kwargs), output.getvalue()


def make_key(directory: Path, name: str) -> tuple[Path, str]:
    key = directory / f"{name}.pem"
    subprocess.run(["openssl", "genpkey", "-algorithm", "ed25519", "-out", str(key)], check=True, capture_output=True)
    der = subprocess.run(["openssl", "pkey", "-in", str(key), "-pubout", "-outform", "DER"],
                         check=True, capture_output=True).stdout
    return key, base64.b64encode(der[-32:]).decode("ascii")


class JarIndexTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)

    def test_entry_fields_match_the_jar_manifest(self):
        fixture = write_extension(self.root, "en", "fixture", uses_lib=True, adult="PARTIAL")
        library = write_library(self.root)
        android = [{"pkg": fixture["package"], "iconUrl": "https://example.test/en-fixture.png"}]
        (index, files), _ = quiet(jar_index.generate, self.root, android, None, 100)
        self.assertEqual((index["format"], index["repository"], index["sequence"]), (1, "io.grimoire.extensions", 100))
        entry, = index["extensions"]
        headers = jar_index.manifest(fixture["jar"])
        self.assertEqual(entry["pkg"], headers["Grimoire-Source-Package"])
        self.assertEqual(entry["sourceClass"], headers["Grimoire-Source-Class"])
        self.assertEqual(str(entry["versionCode"]), headers["Grimoire-Version-Code"])
        self.assertEqual(entry["versionName"], headers["Grimoire-Version-Name"])
        self.assertEqual(str(entry["sourceVersionCode"]), headers["Grimoire-Source-Version-Code"])
        self.assertEqual(entry["coreApi"], headers["Grimoire-Core-Api"])
        self.assertEqual(",".join(entry["hostLibraries"]), headers["Grimoire-Host-Libraries"])
        self.assertEqual((entry["name"], entry["lang"], entry["adultContent"]), ("Fixture Novels", "en", "PARTIAL"))
        self.assertEqual(entry["iconUrl"], "https://example.test/en-fixture.png")
        self.assertEqual(entry["file"], fixture["jar"].name)
        self.assertEqual(index["libraries"], [{"coordinate": LIB, "file": library.name,
                                               "sha256": hashlib.sha256(library.read_bytes()).hexdigest(),
                                               "size": library.stat().st_size}])
        self.assertEqual(files, [fixture["jar"], library])

    def test_digest_and_size_describe_the_exact_bytes(self):
        fixture = write_extension(self.root, "en", "fixture")
        (index, _), _ = quiet(jar_index.generate, self.root, [], None, 1)
        entry, = index["extensions"]
        data = fixture["jar"].read_bytes()
        self.assertEqual(entry["sha256"], hashlib.sha256(data).hexdigest())
        self.assertEqual(entry["size"], len(data))
        self.assertEqual(index["libraries"], [], "host-provided org.json is not published")
        self.assertNotIn("iconUrl", entry)
        self.assertEqual(entry["adultContent"], "NONE")

    def test_extensions_are_ordered_by_package_and_output_is_deterministic(self):
        for lang, name in (("en", "zeta"), ("ar", "beta"), ("en", "alpha"), ("all", "omega")):
            write_extension(self.root, lang, name)
        (first, _), _ = quiet(jar_index.generate, self.root, [], None, 5)
        (second, _), _ = quiet(jar_index.generate, self.root, [], None, 5)
        packages = [entry["pkg"] for entry in first["extensions"]]
        self.assertEqual(packages, sorted(packages))
        self.assertEqual(jar_index.encode(first), jar_index.encode(second))
        self.assertEqual(list(first), ["format", "repository", "sequence", "libraries", "extensions"])
        self.assertEqual([e["lang"] for e in first["extensions"]], ["all", "ar", "en", "en"])

    def test_sequence_always_increases_over_the_previous_index(self):
        write_extension(self.root, "en", "fixture")
        (index, _), _ = quiet(jar_index.generate, self.root, [], {"sequence": 900}, 500)
        self.assertEqual(index["sequence"], 901)
        (index, _), _ = quiet(jar_index.generate, self.root, [], {"sequence": 900}, 1000)
        self.assertEqual(index["sequence"], 1000)

    def test_same_version_with_new_bytes_is_reported(self):
        write_extension(self.root, "en", "fixture")
        (index, _), _ = quiet(jar_index.generate, self.root, [], None, 1)
        previous = json.loads(json.dumps(index))
        previous["extensions"][0]["sha256"] = "0" * 64
        _, output = quiet(jar_index.generate, self.root, [], previous, 2)
        self.assertIn("::warning::io.grimoire.extension.en.fixture 1.0.2 changed bytes", output)

    def test_rejects_artifacts_over_twenty_mebibytes(self):
        self.assertEqual(jar_index.MAX_ARTIFACT_BYTES, 20 * 1024 * 1024)
        fixture = write_extension(self.root, "en", "fixture")
        with fixture["jar"].open("r+b") as handle:
            handle.truncate(jar_index.MAX_ARTIFACT_BYTES + 1)
        with self.assertRaisesRegex(jar_index.JarIndexError, "limit"):
            quiet(jar_index.generate, self.root, [], None, 1)

    def test_rejects_an_oversized_library(self):
        write_extension(self.root, "en", "fixture", uses_lib=True)
        library = write_library(self.root)
        with library.open("r+b") as handle:
            handle.truncate(jar_index.MAX_ARTIFACT_BYTES + 1)
        with self.assertRaisesRegex(jar_index.JarIndexError, "limit"):
            quiet(jar_index.generate, self.root, [], None, 1)

    def test_rejects_identity_that_differs_from_the_manifest(self):
        for field, value in (("versionCode", "4"), ("sourceClass", "io.grimoire.extension.en.fixture.Other"),
                             ("sourceVersionCode", "9"), ("coreApi", "1.5"), ("hostLibraries", LIB),
                             ("module", "en-other")):
            with self.subTest(field=field):
                root = self.root / field
                write_extension(root, "en", "fixture", identity_overrides={field: value})
                with self.assertRaises(jar_index.JarIndexError):
                    quiet(jar_index.generate, root, [], None, 1)

    def test_rejects_a_source_without_a_jar_or_a_missing_library(self):
        write_extension(self.root, "en", "fixture")
        orphan = self.root / "src/en/orphan"
        orphan.mkdir(parents=True)
        (orphan / "build.gradle.kts").write_text("", encoding="utf-8")
        with self.assertRaisesRegex(jar_index.JarIndexError, "en-orphan"):
            quiet(jar_index.generate, self.root, [], None, 1)
        root = self.root / "lib"
        write_extension(root, "en", "fixture", uses_lib=True)
        with self.assertRaisesRegex(jar_index.JarIndexError, "was not built"):
            quiet(jar_index.generate, root, [], None, 1)

    def test_rejects_a_jar_with_foreign_classes(self):
        fixture = write_extension(self.root, "en", "fixture")
        with zipfile.ZipFile(fixture["jar"], "a") as archive:
            archive.writestr("com/example/Other.class", bytes.fromhex("cafebabe00000037"))
        with self.assertRaises(jar_index.JarIndexError):
            quiet(jar_index.generate, self.root, [], None, 1)

    def test_pending_skips_assets_the_release_already_holds(self):
        staging = self.root / "staging"
        staging.mkdir()
        same, changed, new = (staging / f"{name}.jar" for name in ("same", "changed", "new"))
        for path in (same, changed, new):
            path.write_bytes(path.name.encode())
        assets = [{"name": "same.jar", "digest": "sha256:" + hashlib.sha256(b"same.jar").hexdigest()},
                  {"name": "changed.jar", "digest": "sha256:" + "0" * 64}]
        self.assertEqual(jar_index.pending(staging, assets), [changed, new])


@unittest.skipUnless(HAS_OPENSSL, "openssl is required for Ed25519 signing")
class JarIndexSignatureTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.key, self.public = make_key(self.root, "signing")
        self.index = self.root / jar_index.INDEX_NAME
        self.index.write_bytes(jar_index.encode({"format": 1, "repository": jar_index.REPOSITORY, "sequence": 7,
                                                 "libraries": [], "extensions": []}))

    def test_signature_verifies_only_with_the_signing_key(self):
        signature = jar_index.sign(self.index, self.key)
        self.assertEqual(signature.name, "jar-index.json.sig")
        self.assertEqual(len(base64.b64decode(signature.read_bytes(), validate=True)), 64)
        self.assertFalse(signature.read_bytes().endswith(b"\n"))
        self.assertTrue(jar_index.verify(self.index, signature, self.public))
        _, other = make_key(self.root, "other")
        self.assertFalse(jar_index.verify(self.index, signature, other))
        self.assertFalse(jar_index.verify(self.index, signature, jar_index.OFFICIAL_PUBLIC_KEY))

    def test_signature_fails_on_modified_bytes(self):
        signature = jar_index.sign(self.index, self.key)
        data = bytearray(self.index.read_bytes())
        data[-2] ^= 0x01
        self.index.write_bytes(bytes(data))
        self.assertFalse(jar_index.verify(self.index, signature, self.public))
        self.index.write_bytes(self.index.read_bytes() + b" ")
        self.assertFalse(jar_index.verify(self.index, signature, self.public))

    def test_malformed_signature_or_key_is_rejected(self):
        signature = self.root / "bad.sig"
        signature.write_bytes(b"not base64!")
        self.assertFalse(jar_index.verify(self.index, signature, self.public))
        good = jar_index.sign(self.index, self.key)
        self.assertFalse(jar_index.verify(self.index, good, "AAAA"))

    def test_cli_generates_signs_and_rejects_the_wrong_pinned_key(self):
        write_extension(self.root / "repo", "en", "fixture")
        output = self.root / "out"
        run = lambda *args: quiet(jar_index.main, list(args))[0]
        self.assertEqual(run("generate", "--repo-root", str(self.root / "repo"), "--output", str(output),
                             "--sequence", "42"), 0)
        self.assertTrue((output / "io.grimoire.extension.en.fixture-1.0.2.jar").is_file())
        index = output / jar_index.INDEX_NAME
        self.assertEqual(run("sign", "--index", str(index), "--key", str(self.key)), 0)
        self.assertEqual(run("verify", "--index", str(index), "--public-key", self.public), 0)
        self.assertEqual(run("verify", "--index", str(index)), 1, "a non-official key must not pass the pinned check")
        self.assertEqual(json.loads(index.read_text(encoding="utf-8"))["sequence"], 42)


if __name__ == "__main__":
    unittest.main()

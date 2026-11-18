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


def write_library(root: Path, content: bytes = b"lib") -> Path:
    host = root / "jvm/build/host"
    host.mkdir(parents=True, exist_ok=True)
    path = host / "extensions-lib-1.0.0-SNAPSHOT.jar"
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("io/grimoire/extensions/lib/Base.class", content)
    return path


def rebuild_with_new_bytes(fixture: dict) -> None:
    """Same identity, different bytes, as after a compiler or library change."""
    with zipfile.ZipFile(fixture["jar"], "a") as archive:
        archive.writestr(fixture["package"].replace(".", "/") + "/Extra.class", bytes.fromhex("cafebabe00000037"))


def release_assets(index: dict) -> dict:
    """Asset digests of a release that holds every file the index names."""
    entries = index["extensions"] + index["libraries"]
    return {entry["file"]: "sha256:" + entry["sha256"] for entry in entries}


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
        digest = hashlib.sha256(library.read_bytes()).hexdigest()
        published = f"extensions-lib-1.0.0-SNAPSHOT-{digest[:12]}.jar"
        self.assertEqual(index["libraries"], [{"coordinate": LIB, "file": published,
                                               "sha256": digest, "size": library.stat().st_size}])
        self.assertEqual(files, [(fixture["jar"], fixture["jar"].name), (library, published)])

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

    def publish(self, root: Path, previous: dict | None = None, assets: dict | None = None):
        """Generate and return the index, the upload sources and the log."""
        (index, files), output = quiet(jar_index.generate, root, [], previous, 10, assets)
        return index, [source for source, _ in files], output

    def test_published_version_keeps_its_entry_and_skips_upload(self):
        fixture = write_extension(self.root / "old", "en", "fixture")
        previous, _, _ = self.publish(self.root / "old")
        rebuild_with_new_bytes(fixture)
        index, files, output = self.publish(self.root / "old", previous, release_assets(previous))
        self.assertEqual(index["extensions"], previous["extensions"])
        self.assertEqual(files, [])
        self.assertIn("::notice::io.grimoire.extension.en.fixture 1.0.2 rebuilt with different bytes", output)
        self.assertGreater(index["sequence"], previous["sequence"])

    def test_bumped_version_publishes_new_bytes(self):
        write_extension(self.root / "old", "en", "fixture", 3)
        previous, _, _ = self.publish(self.root / "old")
        bumped = write_extension(self.root / "new", "en", "fixture", 4)
        index, files, _ = self.publish(self.root / "new", previous, release_assets(previous))
        entry, = index["extensions"]
        self.assertEqual((entry["versionCode"], entry["file"]), (4, bumped["jar"].name))
        self.assertEqual(entry["sha256"], hashlib.sha256(bumped["jar"].read_bytes()).hexdigest())
        self.assertEqual(files, [bumped["jar"]])

    def test_lower_version_than_published_fails(self):
        write_extension(self.root / "old", "en", "fixture", 4)
        previous, _, _ = self.publish(self.root / "old")
        write_extension(self.root / "new", "en", "fixture", 3)
        with self.assertRaisesRegex(jar_index.JarIndexError, "below the published 4"):
            self.publish(self.root / "new", previous, release_assets(previous))

    def test_missing_previous_asset_publishes_the_rebuilt_bytes(self):
        fixture = write_extension(self.root / "old", "en", "fixture")
        previous, _, _ = self.publish(self.root / "old")
        rebuild_with_new_bytes(fixture)
        index, files, _ = self.publish(self.root / "old", previous, {})
        self.assertEqual(index["extensions"][0]["sha256"], hashlib.sha256(fixture["jar"].read_bytes()).hexdigest())
        self.assertEqual(files, [fixture["jar"]])

    def test_new_bytes_never_replace_a_different_asset_of_the_same_name(self):
        fixture = write_extension(self.root / "old", "en", "fixture")
        previous, _, _ = self.publish(self.root / "old")
        rebuild_with_new_bytes(fixture)
        # The recorded digest no longer matches the asset, so it is not pinned either.
        with self.assertRaisesRegex(jar_index.JarIndexError, "already exists on the release"):
            self.publish(self.root / "old", previous, {fixture["jar"].name: "sha256:" + "1" * 64})
        bumped = write_extension(self.root / "new", "en", "fixture", 4)
        with self.assertRaisesRegex(jar_index.JarIndexError, "already exists on the release"):
            self.publish(self.root / "new", previous,
                         {**release_assets(previous), bumped["jar"].name: "sha256:" + "2" * 64})

    def test_library_change_without_dependent_bumps_fails(self):
        old = self.root / "old"
        write_extension(old, "en", "alpha", 3, uses_lib=True)
        write_extension(old, "en", "beta", 5, uses_lib=True)
        write_extension(old, "en", "plain", 2)
        write_library(old)
        previous, _, _ = self.publish(old)
        new = self.root / "new"
        write_extension(new, "en", "alpha", 4, uses_lib=True)
        write_extension(new, "en", "beta", 5, uses_lib=True)
        write_extension(new, "en", "plain", 2)
        write_library(new, b"changed")
        with self.assertRaises(jar_index.JarIndexError) as raised:
            self.publish(new, previous, release_assets(previous))
        self.assertIn("bump versionCode for: io.grimoire.extension.en.beta", str(raised.exception))
        self.assertNotIn("alpha", str(raised.exception))
        self.assertNotIn("plain", str(raised.exception))

    def test_library_change_with_every_dependent_bumped_publishes(self):
        old = self.root / "old"
        write_extension(old, "en", "alpha", 3, uses_lib=True)
        write_extension(old, "en", "plain", 2)
        write_library(old)
        previous, _, _ = self.publish(old)
        new = self.root / "new"
        alpha = write_extension(new, "en", "alpha", 4, uses_lib=True)
        write_extension(new, "en", "plain", 2)
        library = write_library(new, b"changed")
        index, files, _ = self.publish(new, previous, release_assets(previous))
        digest = hashlib.sha256(library.read_bytes()).hexdigest()
        self.assertEqual(index["libraries"][0]["sha256"], digest)
        self.assertEqual(files, [alpha["jar"], library])
        # A changed library gets a new file name, so the old index keeps its bytes.
        self.assertEqual(index["libraries"][0]["file"], f"extensions-lib-1.0.0-SNAPSHOT-{digest[:12]}.jar")
        self.assertNotEqual(index["libraries"][0]["file"], previous["libraries"][0]["file"])
        self.assertIn(previous["libraries"][0]["file"], release_assets(previous))
        self.assertEqual([e["sha256"] for e in index["extensions"] if e["pkg"].endswith("plain")],
                         [e["sha256"] for e in previous["extensions"] if e["pkg"].endswith("plain")])

    def test_unchanged_library_is_not_uploaded_again(self):
        write_extension(self.root, "en", "alpha", uses_lib=True)
        write_library(self.root)
        previous, _, _ = self.publish(self.root)
        index, files, _ = self.publish(self.root, previous, release_assets(previous))
        self.assertEqual(index["libraries"], previous["libraries"])
        self.assertEqual(files, [])
        self.assertTrue(jar_index.unchanged(index, previous))

    def plan(self, previous: dict, assets: dict, canonical: bool = True):
        (index, files), _ = quiet(jar_index.generate, self.root, [], previous, 20, assets)
        return index, jar_index.publication(index, previous, canonical, files, assets)

    def test_missing_extension_jar_is_restored_without_a_new_index(self):
        fixture = write_extension(self.root, "en", "fixture")
        previous, _, _ = self.publish(self.root)
        assets = release_assets(previous)
        del assets[fixture["jar"].name]
        index, (missing, new_index) = self.plan(previous, assets)
        self.assertTrue(jar_index.unchanged(index, previous))
        self.assertEqual((missing, new_index), ([fixture["jar"].name], False))

    def test_missing_library_is_restored_without_a_new_index(self):
        write_extension(self.root, "en", "alpha", uses_lib=True)
        write_library(self.root)
        previous, _, _ = self.publish(self.root)
        assets = release_assets(previous)
        library = previous["libraries"][0]["file"]
        del assets[library]
        index, (missing, new_index) = self.plan(previous, assets)
        self.assertEqual(index["libraries"], previous["libraries"])
        self.assertEqual((missing, new_index), ([library], False))

    def test_unchanged_run_publishes_nothing(self):
        write_extension(self.root, "en", "alpha", uses_lib=True)
        write_library(self.root)
        previous, _, _ = self.publish(self.root)
        _, plan = self.plan(previous, release_assets(previous))
        self.assertEqual(plan, ([], False))
        # A stale canonical pair still needs a new index pair, with nothing else to upload.
        _, plan = self.plan(previous, release_assets(previous), canonical=False)
        self.assertEqual(plan, ([], True))

    def test_generate_reports_the_publication_plan(self):
        fixture = write_extension(self.root, "en", "fixture")
        previous, _, _ = self.publish(self.root)
        (self.root / "previous.json").write_bytes(jar_index.encode(previous))
        assets = [{"name": name, "digest": digest} for name, digest in release_assets(previous).items()
                  if name != fixture["jar"].name]
        (self.root / "assets.json").write_text(json.dumps(assets), encoding="utf-8")
        outputs = self.root / "outputs.txt"
        code, _ = quiet(jar_index.main, ["generate", "--repo-root", str(self.root), "--output", str(self.root / "out"),
                                         "--previous", str(self.root / "previous.json"), "--canonical", "true",
                                         "--assets", str(self.root / "assets.json"), "--sequence", "30",
                                         "--github-output", str(outputs)])
        self.assertEqual(code, 0)
        self.assertEqual(outputs.read_text(encoding="utf-8").splitlines(),
                         ["sequence=30", "artifacts=true", "index=false"])
        self.assertTrue((self.root / "out" / fixture["jar"].name).is_file())

    def test_library_file_name_is_content_addressed(self):
        write_extension(self.root, "en", "alpha", uses_lib=True)
        library = write_library(self.root)
        (index, files), _ = quiet(jar_index.generate, self.root, [], None, 1)
        digest = hashlib.sha256(library.read_bytes()).hexdigest()
        self.assertEqual(index["libraries"][0]["file"], f"extensions-lib-1.0.0-SNAPSHOT-{digest[:12]}.jar")
        self.assertEqual(index["libraries"][0]["coordinate"], LIB)
        output = self.root / "staged"
        self.assertEqual(quiet(jar_index.main, ["generate", "--repo-root", str(self.root), "--output", str(output),
                                                "--sequence", "1"])[0], 0)
        self.assertTrue((output / index["libraries"][0]["file"]).is_file())
        self.assertFalse((output / library.name).exists())

    def test_old_versioned_index_pairs_are_pruned(self):
        names = {f"jar-index-{n}.json{suffix}" for n in range(1, 7) for suffix in ("", ".sig")}
        names |= {"jar-index.json", "jar-index.json.sig", "x.jar"}
        self.assertEqual(jar_index.stale_versions(names, 4),
                         ["jar-index-1.json", "jar-index-1.json.sig", "jar-index-2.json", "jar-index-2.json.sig"])
        self.assertEqual(jar_index.stale_versions(names, 10), [])

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


@unittest.skipUnless(HAS_OPENSSL, "openssl is required for Ed25519 signing")
class JarIndexRecoveryTest(unittest.TestCase):
    """The release as seen by the next publication, after complete or interrupted uploads."""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.key, self.public = make_key(self.root, "signing")
        self.release = self.root / "release"
        self.release.mkdir()

    def publish_pair(self, sequence: int, key: Path | None = None) -> None:
        """Upload the versioned pair, then replace the canonical pair, as the workflow does."""
        staging = Path(tempfile.mkdtemp(dir=self.root))
        index = staging / jar_index.INDEX_NAME
        index.write_bytes(jar_index.encode({"format": 1, "repository": jar_index.REPOSITORY,
                                            "sequence": sequence, "libraries": [], "extensions": []}))
        jar_index.sign(index, key or self.key)
        versioned = jar_index.versioned_name(sequence)
        for suffix in ("", ".sig"):
            shutil.copyfile(staging / (jar_index.INDEX_NAME + suffix), self.release / (versioned + suffix))
            shutil.copyfile(staging / (jar_index.INDEX_NAME + suffix), self.release / (jar_index.INDEX_NAME + suffix))

    def recover(self):
        names = {path.name for path in self.release.iterdir()}
        (result, output) = quiet(jar_index.recover, self.release, names, self.public)
        path, canonical = result
        return (path.name if path else None), canonical, output

    def test_complete_publication_uses_the_canonical_pair(self):
        self.publish_pair(10)
        self.publish_pair(20)
        self.assertEqual(self.recover()[:2], ("jar-index.json", True))

    def test_mismatched_canonical_pair_recovers_from_the_versioned_pair(self):
        self.publish_pair(10)
        old_signature = (self.release / "jar-index.json.sig").read_bytes()
        self.publish_pair(20)
        # Interrupted after the canonical index replaced, before its signature.
        (self.release / "jar-index.json.sig").write_bytes(old_signature)
        name, canonical, output = self.recover()
        self.assertEqual((name, canonical), ("jar-index-20.json", False))
        self.assertIn("::warning::jar-index.json does not verify", output)
        self.assertIn("recovering from jar-index-20.json", output)

    def test_interrupted_before_the_canonical_swap_uses_the_newer_versioned_pair(self):
        self.publish_pair(10)
        self.publish_pair(20)
        for suffix in ("", ".sig"):
            shutil.copyfile(self.release / f"jar-index-10.json{suffix}", self.release / f"jar-index.json{suffix}")
        self.assertEqual(self.recover()[:2], ("jar-index-20.json", False))

    def test_missing_canonical_pair_recovers_and_unpaired_files_are_ignored(self):
        self.publish_pair(10)
        (self.release / "jar-index.json").unlink()
        (self.release / "jar-index.json.sig").unlink()
        (self.release / "jar-index-30.json").write_bytes(b"{}")
        name, canonical, output = self.recover()
        self.assertEqual((name, canonical), ("jar-index-10.json", False))
        self.assertIn("::warning::jar-index-30.json has no signature", output)

    def test_fails_when_pairs_exist_but_none_verifies(self):
        self.publish_pair(10, make_key(self.root, "foreign")[0])
        with self.assertRaisesRegex(jar_index.JarIndexError, "none verifies"):
            self.recover()
        # A validly signed index renamed to another sequence is not trusted either.
        shutil.rmtree(self.release)
        self.release.mkdir()
        self.publish_pair(10)
        (self.release / "jar-index.json").unlink()
        (self.release / "jar-index.json.sig").unlink()
        for suffix in ("", ".sig"):
            (self.release / f"jar-index-10.json{suffix}").rename(self.release / f"jar-index-11.json{suffix}")
        with self.assertRaisesRegex(jar_index.JarIndexError, "none verifies"):
            self.recover()

    def test_canonical_index_without_any_verifying_pair_fails(self):
        (self.release / "jar-index.json").write_bytes(b"{}")
        with self.assertRaisesRegex(jar_index.JarIndexError, "none verifies"):
            self.recover()

    def test_first_publication_bootstraps_when_nothing_exists(self):
        self.assertEqual(self.recover()[:2], (None, False))
        (self.release / "jar-index-5.json").write_bytes(b"{}")
        self.assertEqual(self.recover()[:2], (None, False), "an unpaired leftover is not a publication")

    def test_listed_but_undownloaded_pair_fails(self):
        with self.assertRaisesRegex(jar_index.JarIndexError, "was not downloaded"):
            quiet(jar_index.recover, self.release, {"jar-index.json", "jar-index.json.sig"}, self.public)

    def test_cli_writes_the_recovered_index_for_the_next_steps(self):
        self.publish_pair(10)
        (self.release / "jar-index.json.sig").unlink()
        assets = self.root / "assets.json"
        assets.write_text(json.dumps([{"name": path.name, "digest": None} for path in self.release.iterdir()]),
                          encoding="utf-8")
        outputs = self.root / "outputs.txt"
        code, _ = quiet(jar_index.main, ["recover", "--published", str(self.release), "--assets", str(assets),
                                         "--public-key", self.public, "--github-output", str(outputs)])
        self.assertEqual(code, 0)
        lines = outputs.read_text(encoding="utf-8").splitlines()
        self.assertEqual(lines, [f"previous={(self.release / 'jar-index-10.json').as_posix()}", "canonical=false"])


if __name__ == "__main__":
    unittest.main()

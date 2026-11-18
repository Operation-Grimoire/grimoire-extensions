"""Generate, sign and verify the Desktop JAR index (format 1).

The index lists the thin JARs that `./gradlew -p jvm jars` writes under
jvm/build/extensions. Identity fields come from each JAR's Grimoire-* manifest
and must equal the identity.json written beside it. name, lang and adultContent
come from @SourceInfo, read the same way the Android index generator reads them.
iconUrl is copied from the published Android index.json.

A version that is already published keeps its entry and bytes; see generate().
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_dual import manifest, validate_jar  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "io.grimoire.extensions"
OFFICIAL_PUBLIC_KEY = "PYUsj2AvddEkNAiO8Vc2tPskScoilyAdbMG8ZNN/IfM="
INDEX_NAME = "jar-index.json"
SIGNATURE_NAME = INDEX_NAME + ".sig"
MAX_ARTIFACT_BYTES = 20 * 1024 * 1024
# Supplied by the Desktop host, so never published as a library.
HOST_PROVIDED = {"org.json:json:20240303"}
ED25519_SPKI_PREFIX = bytes.fromhex("302a300506032b6570032100")

# identity.json key -> JAR manifest attribute it mirrors.
ATTRIBUTES = {
    "package": "Grimoire-Source-Package",
    "sourceClass": "Grimoire-Source-Class",
    "versionCode": "Grimoire-Version-Code",
    "versionName": "Grimoire-Version-Name",
    "sourceVersionCode": "Grimoire-Source-Version-Code",
    "coreApi": "Grimoire-Core-Api",
    "hostLibraries": "Grimoire-Host-Libraries",
}
SOURCE_INFO = re.compile(r"@SourceInfo\s*\((.*?)\)", re.DOTALL)
COORDINATE = re.compile(r"[\w.-]+:[\w.-]+:[\w.-]+")


class JarIndexError(ValueError):
    pass


def extension_dirs(root: Path) -> list[Path]:
    return sorted(path.parent for path in (root / "src").glob("*/*/build.gradle.kts"))


def artifact(path: Path) -> dict:
    size = path.stat().st_size
    if not 0 < size <= MAX_ARTIFACT_BYTES:
        raise JarIndexError(f"{path.name} is {size} bytes; the limit is {MAX_ARTIFACT_BYTES}")
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    return {"file": path.name, "sha256": digest, "size": size}


def source_metadata(directory: Path, language: str) -> dict:
    for path in sorted((directory / "src/main/java").rglob("*.kt")):
        match = SOURCE_INFO.search(path.read_text(encoding="utf-8"))
        if not match:
            continue
        annotation = match.group(1)

        def string(key: str) -> str | None:
            found = re.search(rf'\b{key}\s*=\s*"([^"]*)"', annotation)
            return found.group(1) if found else None

        name = string("name")
        if not name:
            raise JarIndexError(f"{directory} @SourceInfo has no name")
        # An enum lang (Language.EN) falls back to the directory, as in index.json.
        adult = re.search(r"\badultContent\s*=\s*[A-Za-z_][\w.]*\.(\w+)", annotation)
        return {"name": name, "lang": string("lang") or language,
                "adultContent": adult.group(1) if adult else "NONE"}
    raise JarIndexError(f"{directory} has no @SourceInfo")


def jar_identity(module_dir: Path, module: str) -> tuple[Path, dict[str, str]]:
    identity_path = module_dir / "identity.json"
    if not identity_path.is_file():
        raise JarIndexError(f"{module} has no identity.json; run ./gradlew -p jvm jars")
    identity = json.loads(identity_path.read_text(encoding="utf-8"))
    if identity.get("module") != module:
        raise JarIndexError(f"{module} identity.json names module {identity.get('module')}")
    jar = module_dir / f"{identity.get('package')}-{identity.get('versionName')}.jar"
    if not jar.is_file():
        raise JarIndexError(f"{module} has no {jar.name}")
    artifact(jar)  # size gate before reading the archive
    attrs = manifest(jar)
    if attrs.get("Grimoire-Extension-Format") != "1":
        raise JarIndexError(f"{jar.name} is not extension format 1")
    values = {}
    for key, header in ATTRIBUTES.items():
        if header not in attrs or attrs[header] != identity.get(key):
            raise JarIndexError(f"{jar.name} {header} does not match identity.json {key}")
        values[key] = attrs[header]
    try:
        validate_jar(jar, values)
    except ValueError as error:
        raise JarIndexError(f"{jar.name}: {error}") from error
    return jar, values


def positive(value: str, label: str) -> int:
    if not re.fullmatch(r"[1-9]\d*", value):
        raise JarIndexError(f"{label} must be a positive integer, got {value}")
    return int(value)


def published(entry: dict, assets: dict) -> bool:
    """The release still holds this entry's file with the recorded digest."""
    return assets.get(entry.get("file")) == f"sha256:{entry.get('sha256')}"


def unclaimed(entry: dict, assets: dict) -> None:
    """New bytes may not replace a different asset of the same name."""
    existing = assets.get(entry["file"])
    if existing and existing != f"sha256:{entry['sha256']}":
        raise JarIndexError(f"{entry['file']} already exists on the release with different bytes")


def rebuilt_entries(root: Path, android_index: list | None) -> list[tuple[dict, Path]]:
    icons = {entry["pkg"]: entry["iconUrl"] for entry in android_index or []
             if isinstance(entry, dict) and str(entry.get("iconUrl", "")).startswith("https://")}
    directories = extension_dirs(root)
    if not directories:
        raise JarIndexError(f"No extension directories under {root / 'src'}")
    entries = []
    for directory in directories:
        language = directory.parent.name
        module = f"{language}-{directory.name}"
        jar, values = jar_identity(root / "jvm/build/extensions" / module, module)
        libraries = [item.strip() for item in values["hostLibraries"].split(",") if item.strip()]
        for coordinate in libraries:
            if not COORDINATE.fullmatch(coordinate):
                raise JarIndexError(f"{module} declares malformed host library {coordinate}")
        meta = source_metadata(directory, language)
        entry = {
            "pkg": values["package"], "name": meta["name"], "lang": meta["lang"],
            "sourceClass": values["sourceClass"],
            "versionCode": positive(values["versionCode"], f"{module} versionCode"),
            "versionName": values["versionName"],
            "sourceVersionCode": positive(values["sourceVersionCode"], f"{module} sourceVersionCode"),
            "coreApi": values["coreApi"], "hostLibraries": libraries,
            **artifact(jar),
            "adultContent": meta["adultContent"],
        }
        if entry["pkg"] in icons:
            entry["iconUrl"] = icons[entry["pkg"]]
        else:
            print(f"::warning::{module} has no iconUrl in the Android index")
        entries.append((entry, jar))
    packages = [entry["pkg"] for entry, _ in entries]
    if len(set(packages)) != len(packages):
        raise JarIndexError("Two extensions declare the same package")
    return entries


def generate(root: Path, android_index: list | None, previous: dict | None,
             sequence: int | None = None, assets: dict | None = None) -> tuple[dict, list[Path]]:
    """Return the index and the files that must be uploaded for it.

    A published version keeps its previous entry and bytes. New bytes ship only
    with a higher versionCode, because Desktop treats an equal versionCode with
    another digest as a conflict.
    """
    assets = assets or {}
    previous = previous or {}
    old_extensions = {entry["pkg"]: entry for entry in previous.get("extensions", [])
                      if isinstance(entry, dict) and "pkg" in entry}
    old_libraries = {entry["coordinate"]: entry for entry in previous.get("libraries", [])
                     if isinstance(entry, dict) and "coordinate" in entry}
    extensions, files, new_versions = [], [], set()
    for entry, jar in rebuilt_entries(root, android_index):
        before = old_extensions.get(entry["pkg"])
        if before:
            if entry["versionCode"] < before["versionCode"]:
                raise JarIndexError(f"{entry['pkg']} versionCode {entry['versionCode']} is below the "
                                    f"published {before['versionCode']}")
            if entry["versionCode"] == before["versionCode"] and published(before, assets):
                if entry["sha256"] != before["sha256"]:
                    print(f"::notice::{entry['pkg']} {before['versionName']} rebuilt with different bytes; "
                          "keeping the published JAR")
                extensions.append(dict(before))
                continue
        if not before or entry["versionCode"] > before["versionCode"]:
            new_versions.add(entry["pkg"])
        unclaimed(entry, assets)
        extensions.append(entry)
        files.append(jar)
    extensions.sort(key=lambda entry: entry["pkg"])

    libraries = []
    for coordinate in sorted({c for entry in extensions for c in entry["hostLibraries"]}):
        if not COORDINATE.fullmatch(coordinate):
            raise JarIndexError(f"Malformed host library {coordinate}")
        _, name, version = coordinate.split(":")
        path = root / "jvm/build/host" / f"{name}-{version}.jar"
        before = old_libraries.get(coordinate)
        kept = before is not None and published(before, assets)
        if not path.is_file():
            if kept:
                libraries.append(dict(before))
            elif coordinate not in HOST_PROVIDED:
                raise JarIndexError(f"Library {coordinate} is required but {path.name} was not built")
            continue
        rebuilt = {"coordinate": coordinate, **artifact(path)}
        if kept and rebuilt["sha256"] == before["sha256"]:
            libraries.append(dict(before))
            continue
        if kept:
            # One library per coordinate: every dependent must ship a new version with it.
            stale = sorted(entry["pkg"] for entry in extensions
                           if coordinate in entry["hostLibraries"] and entry["pkg"] not in new_versions)
            if stale:
                raise JarIndexError(f"{coordinate} changed; bump versionCode for: {', '.join(stale)}")
            print(f"::notice::{coordinate} changed and every dependent was bumped; publishing it")
        else:
            unclaimed(rebuilt, assets)
        libraries.append(rebuilt)
        files.append(path)

    now = int(time.time()) if sequence is None else sequence
    if isinstance(previous.get("sequence"), int) and previous["sequence"] >= now:
        now = previous["sequence"] + 1
    index = {"format": 1, "repository": REPOSITORY, "sequence": now,
             "libraries": libraries, "extensions": extensions}
    return index, files


def asset_digests(assets: list | None) -> dict:
    return {asset.get("name"): asset.get("digest") for asset in assets or [] if isinstance(asset, dict)}


def encode(index: dict) -> bytes:
    return (json.dumps(index, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def openssl(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["openssl", *args], capture_output=True, text=True)


def sign(index_path: Path, key_path: Path) -> Path:
    """Write base64(raw Ed25519 signature) beside the index."""
    with tempfile.TemporaryDirectory() as directory:
        raw = Path(directory) / "signature.bin"
        result = openssl("pkeyutl", "-sign", "-rawin", "-inkey", str(key_path),
                         "-in", str(index_path), "-out", str(raw))
        if result.returncode != 0 or not raw.is_file():
            raise JarIndexError(f"openssl could not sign the index: {result.stderr.strip()}")
        signature = raw.read_bytes()
    if len(signature) != 64:
        raise JarIndexError("The signing key is not an Ed25519 key")
    output = index_path.with_name(index_path.name + ".sig")
    output.write_bytes(base64.b64encode(signature))
    return output


def verify(index_path: Path, signature_path: Path, public_key: str) -> bool:
    """Check the signature against a raw base64 Ed25519 public key."""
    try:
        key = base64.b64decode(public_key.strip(), validate=True)
        signature = base64.b64decode(signature_path.read_bytes().strip(), validate=True)
    except (OSError, ValueError):
        return False
    if len(key) != 32 or len(signature) != 64:
        return False
    with tempfile.TemporaryDirectory() as directory:
        pem = Path(directory) / "public.pem"
        body = base64.encodebytes(ED25519_SPKI_PREFIX + key).decode("ascii")
        pem.write_text(f"-----BEGIN PUBLIC KEY-----\n{body}-----END PUBLIC KEY-----\n", encoding="ascii")
        raw = Path(directory) / "signature.bin"
        raw.write_bytes(signature)
        result = openssl("pkeyutl", "-verify", "-rawin", "-pubin", "-inkey", str(pem),
                         "-in", str(index_path), "-sigfile", str(raw))
    return result.returncode == 0 and "Signature Verified Successfully" in result.stdout


def pending(staging: Path, assets: list) -> list[Path]:
    """Artifacts that are missing from the release or differ from the asset there."""
    digests = asset_digests(assets)
    return [path for path in sorted(staging.glob("*.jar"))
            if digests.get(path.name) != "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()]


def load_json(path: str | None):
    if not path or not Path(path).is_file():
        return None
    return json.loads(Path(path).read_text(encoding="utf-8"))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    make = commands.add_parser("generate", help="Stage new JARs and write jar-index.json")
    make.add_argument("--repo-root", default=str(ROOT))
    make.add_argument("--android-index", help="Published Android index.json, for iconUrl")
    make.add_argument("--previous", help="Previously published, verified jar-index.json, if any")
    make.add_argument("--assets", help="JSON list of {name, digest} assets on the release")
    make.add_argument("--sequence", type=int, help="Override the epoch-seconds sequence")
    make.add_argument("--output", required=True, help="Staging directory")
    signer = commands.add_parser("sign", help="Sign jar-index.json with an Ed25519 PEM key")
    signer.add_argument("--index", required=True)
    signer.add_argument("--key", required=True)
    checker = commands.add_parser("verify", help="Verify jar-index.json.sig")
    checker.add_argument("--index", required=True)
    checker.add_argument("--signature")
    checker.add_argument("--public-key", default=OFFICIAL_PUBLIC_KEY)
    uploads = commands.add_parser("pending", help="List staged JARs the release does not hold yet")
    uploads.add_argument("--staging", required=True)
    uploads.add_argument("--assets", required=True, help="JSON list of {name, digest} release assets")
    args = parser.parse_args(argv)

    try:
        if args.command == "generate":
            index, files = generate(Path(args.repo_root), load_json(args.android_index),
                                    load_json(args.previous), args.sequence,
                                    asset_digests(load_json(args.assets)))
            output = Path(args.output)
            output.mkdir(parents=True, exist_ok=True)
            for path in files:
                shutil.copyfile(path, output / path.name)
            (output / INDEX_NAME).write_bytes(encode(index))
            print(f"{INDEX_NAME}: {len(index['extensions'])} extension(s), "
                  f"{len(index['libraries'])} library(ies), sequence {index['sequence']}")
        elif args.command == "sign":
            print(f"Signed: {sign(Path(args.index), Path(args.key))}")
        elif args.command == "verify":
            index = Path(args.index)
            signature = Path(args.signature) if args.signature else index.with_name(index.name + ".sig")
            if not verify(index, signature, args.public_key):
                print(f"::error::{signature.name} does not verify with public key {args.public_key}")
                return 1
            print(f"Verified {index.name} with public key {args.public_key}")
        else:
            for path in pending(Path(args.staging), load_json(args.assets) or []):
                print(path.as_posix())
    except ValueError as error:
        print(f"::error::{error}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

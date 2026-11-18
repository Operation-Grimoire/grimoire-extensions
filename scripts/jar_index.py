"""Generate, sign and verify the Desktop JAR index (format 1).

The index lists the thin JARs that `./gradlew -p jvm jars` writes under
jvm/build/extensions. Identity fields come from each JAR's Grimoire-* manifest
and must equal the identity.json written beside it. name, lang and adultContent
come from @SourceInfo, read the same way the Android index generator reads them.
iconUrl is copied from the published Android index.json.
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


def generate(root: Path, android_index: list | None, previous: dict | None,
             sequence: int | None = None) -> tuple[dict, list[Path]]:
    """Return the index and every file it references."""
    icons = {entry["pkg"]: entry["iconUrl"] for entry in android_index or []
             if isinstance(entry, dict) and str(entry.get("iconUrl", "")).startswith("https://")}
    build = root / "jvm" / "build"
    extensions, files, required = [], [], set()
    directories = extension_dirs(root)
    if not directories:
        raise JarIndexError(f"No extension directories under {root / 'src'}")
    for directory in directories:
        language = directory.parent.name
        module = f"{language}-{directory.name}"
        jar, values = jar_identity(build / "extensions" / module, module)
        libraries = [item.strip() for item in values["hostLibraries"].split(",") if item.strip()]
        for coordinate in libraries:
            if not COORDINATE.fullmatch(coordinate):
                raise JarIndexError(f"{module} declares malformed host library {coordinate}")
        required.update(libraries)
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
        extensions.append(entry)
        files.append(jar)
    packages = [entry["pkg"] for entry in extensions]
    if len(set(packages)) != len(packages):
        raise JarIndexError("Two extensions declare the same package")
    extensions.sort(key=lambda entry: entry["pkg"])

    library_entries = []
    for coordinate in sorted(required):
        _, name, version = coordinate.split(":")
        path = build / "host" / f"{name}-{version}.jar"
        if path.is_file():
            library_entries.append({"coordinate": coordinate, **artifact(path)})
            files.append(path)
        elif coordinate not in HOST_PROVIDED:
            raise JarIndexError(f"Library {coordinate} is required but {path.name} was not built")

    now = int(time.time()) if sequence is None else sequence
    if previous and isinstance(previous.get("sequence"), int) and previous["sequence"] >= now:
        now = previous["sequence"] + 1
    index = {"format": 1, "repository": REPOSITORY, "sequence": now,
             "libraries": library_entries, "extensions": extensions}
    for warning in drift(previous, index):
        print(f"::warning::{warning}")
    return index, files


def drift(previous: dict | None, index: dict) -> list[str]:
    """Same-version artifacts whose bytes changed, and version regressions."""
    if not previous:
        return []
    warnings = []
    old = {entry.get("pkg"): entry for entry in previous.get("extensions", []) if isinstance(entry, dict)}
    for entry in index["extensions"]:
        before = old.get(entry["pkg"])
        if not before:
            continue
        if entry["versionCode"] == before.get("versionCode") and entry["sha256"] != before.get("sha256"):
            warnings.append(f"{entry['pkg']} {entry['versionName']} changed bytes without a versionCode bump")
        elif entry["versionCode"] < before.get("versionCode", 0):
            warnings.append(f"{entry['pkg']} versionCode went down from {before.get('versionCode')}")
    return warnings


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
    except ValueError:
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
    published = {asset.get("name"): asset.get("digest") for asset in assets if isinstance(asset, dict)}
    return [path for path in sorted(staging.glob("*.jar"))
            if published.get(path.name) != "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()]


def load_json(path: str | None):
    if not path or not Path(path).is_file():
        return None
    return json.loads(Path(path).read_text(encoding="utf-8"))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    make = commands.add_parser("generate", help="Stage the JARs and write jar-index.json")
    make.add_argument("--repo-root", default=str(ROOT))
    make.add_argument("--android-index", help="Published Android index.json, for iconUrl")
    make.add_argument("--previous", help="Previously published jar-index.json, if any")
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
                                    load_json(args.previous), args.sequence)
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

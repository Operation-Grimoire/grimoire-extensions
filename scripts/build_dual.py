"""Build and verify every extension APK and JAR without publishing anything."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def manifest(path: Path) -> dict[str, str]:
    with zipfile.ZipFile(path) as jar:
        text = jar.read("META-INF/MANIFEST.MF").decode("utf-8").replace("\r\n", "\n").replace("\n ", "")
    return dict(line.split(": ", 1) for line in text.split("\n\n", 1)[0].splitlines() if ": " in line)


def extension_dirs() -> list[Path]:
    return sorted(path.parent for path in (ROOT / "src").rglob("build.gradle.kts"))


def module_name(directory: Path) -> str:
    return f"{directory.parent.name}-{directory.name}"


def validate_jar(jar: Path, expected: dict[str, str]) -> dict[str, str]:
    attrs = manifest(jar)
    pairs = {"package": "Grimoire-Source-Package", "sourceClass": "Grimoire-Source-Class",
             "versionCode": "Grimoire-Version-Code", "versionName": "Grimoire-Version-Name",
             "sourceVersionCode": "Grimoire-Source-Version-Code"}
    for key, header in pairs.items():
        if attrs.get(header) != expected[key]:
            raise ValueError(f"JAR {header} differs from the extension Android metadata")
    if attrs.get("Grimoire-Extension-Format") != "1" or attrs.get("Grimoire-Core-Api") != "1.4":
        raise ValueError("Unsupported JAR format/core API")
    with zipfile.ZipFile(jar) as archive:
        classes = [name for name in archive.namelist() if name.endswith(".class")]
        entry = expected["sourceClass"].replace(".", "/") + ".class"
        if entry not in classes or any(not name.startswith(expected["package"].replace(".", "/") + "/") for name in classes):
            raise ValueError("JAR must contain only this extension's classes, with a valid entry point")
        for name in classes:
            code = archive.read(name)
            if int.from_bytes(code[6:8], "big") != 55 or b"android/" in code or b"android." in code:
                raise ValueError(f"JAR class must target Java 11 without Android references: {name}")
    return attrs


def validate(jar: Path, apk: Path, aapt2: Path, expected: dict[str, str]) -> dict[str, str]:
    attrs = validate_jar(jar, expected)
    badging = subprocess.check_output([str(aapt2), "dump", "badging", str(apk)], text=True, encoding="utf-8")
    match = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not match or match.groups() != (expected["package"], expected["versionCode"], expected["versionName"]):
        raise ValueError("APK package/version differs from JAR identity")
    tree = subprocess.check_output([str(aapt2), "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml"], text=True, encoding="utf-8")
    if not re.search(r':name[^\n]*"grimoire.extension.class"[^\n]*\n\s*A: [^\n]*:value[^\n]*"' +
                     re.escape(expected["sourceClass"]) + r'"', tree):
        raise ValueError("APK source entry point differs from JAR identity")
    return attrs


def find_aapt2(explicit: str | None) -> Path:
    if explicit:
        return Path(explicit).resolve()
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise ValueError("Set ANDROID_HOME or pass --aapt2")
    candidates = list((Path(sdk) / "build-tools").glob("*/aapt2.exe" if os.name == "nt" else "*/aapt2"))
    if not candidates:
        raise ValueError("Install Android SDK build-tools or pass --aapt2")
    return sorted(candidates, key=lambda path: tuple(int(n) for n in re.findall(r"\d+", path.parent.name)))[-1]


def royal_road_contract(jar: Path, expected: dict[str, str]) -> dict:
    report_path = ROOT / "jvm/build/extensions/en-royalroad/contract-jvm.json"
    jvm_report = json.loads(report_path.read_text(encoding="utf-8"))
    contract = jvm_report["contract"]
    if jvm_report["jarSha256"] != hashlib.sha256(jar.read_bytes()).hexdigest():
        raise ValueError("JVM contract proof belongs to a different JAR")
    if str(contract["sourceVersionCode"]) != expected["sourceVersionCode"] or contract["sourceClass"] != expected["sourceClass"]:
        raise ValueError("Packaged source annotation differs from artifact metadata")
    return jvm_report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api", help="API checkout containing extensions-core (opt-in composite)")
    parser.add_argument("--aapt2", help="Android build-tools aapt2 binary")
    parser.add_argument("--variant", choices=("debug", "release"), default="debug")
    parser.add_argument("--verify-only", action="store_true", help="Verify artifacts already built at the standard paths")
    parser.add_argument("--jars-only", action="store_true", help="Build and check JARs without assembling APKs")
    args = parser.parse_args()
    directories = extension_dirs()
    if not directories:
        raise ValueError("No extension directories under src/")
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    options = ["--no-build-cache", "--console=plain"]
    if args.api:
        options.append(f"-PgrimoireApiDir={Path(args.api).resolve().as_posix()}")
    if not args.verify_only:
        if not args.jars_only:
            variant = args.variant.capitalize()
            subprocess.run([str(wrapper), f"assemble{variant}", f":en-royalroad:test{variant}UnitTest", *options], cwd=ROOT, check=True)
        subprocess.run([str(wrapper), "-p", "jvm", "jars", "test", *options], cwd=ROOT, check=True)
    aapt2 = None if args.jars_only else find_aapt2(args.aapt2)
    for directory in directories:
        name = module_name(directory)
        identity_path = ROOT / "jvm" / "build" / "extensions" / name / "identity.json"
        identity = json.loads(identity_path.read_text(encoding="utf-8"))
        expected = {key: str(identity[key]) for key in
                    ("package", "sourceClass", "versionCode", "versionName", "sourceVersionCode")}
        jar = ROOT / "jvm" / "build" / "extensions" / name / f"{expected['package']}-{expected['versionName']}.jar"
        if args.jars_only:
            validate_jar(jar, expected)
            continue
        apk_files = list((directory / "build" / "outputs" / "apk" / args.variant).glob("*.apk"))
        if len(apk_files) != 1:
            raise ValueError(f"Expected one {args.variant} APK for {name}; found {len(apk_files)}")
        apk = apk_files[0]
        attrs = validate(jar, apk, aapt2, expected)
        report = {"format": 1, "module": name, "identity": expected,
                  "jvmCoreApi": attrs["Grimoire-Core-Api"],
                  "androidRuntimeParity": "UNEXECUTED by this build command"}
        if name == "en-royalroad":
            contract = royal_road_contract(jar, expected)["contract"]
            report["sourceId"] = contract["sourceId"]
        output = ROOT / "build" / "dual" / name
        output.mkdir(parents=True, exist_ok=True)
        artifacts = {}
        for kind, path in (("android", apk), ("jvm", jar)):
            shutil.copy2(path, output / path.name)
            artifacts[kind] = {"file": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
        report["artifacts"] = artifacts
        (output / "artifacts.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"Verified {name}: {output}")
    if args.jars_only:
        royal = json.loads((ROOT / "jvm/build/extensions/en-royalroad/identity.json").read_text(encoding="utf-8"))
        jar = ROOT / "jvm/build/extensions/en-royalroad" / f"{royal['package']}-{royal['versionName']}.jar"
        expected = {key: str(royal[key]) for key in ("sourceClass", "sourceVersionCode")}
        royal_road_contract(jar, expected)
        print(f"Verified {len(directories)} JARs and the Royal Road contract")


if __name__ == "__main__":
    main()

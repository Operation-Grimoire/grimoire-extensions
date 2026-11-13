"""Build and verify the Royal Road Android/JVM artifacts without publishing anything."""
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


def properties(path: Path) -> dict[str, str]:
    return dict(line.strip().split("=", 1) for line in path.read_text(encoding="utf-8").splitlines()
                if line.strip() and not line.lstrip().startswith("#"))


def manifest(path: Path) -> dict[str, str]:
    with zipfile.ZipFile(path) as jar:
        text = jar.read("META-INF/MANIFEST.MF").decode("utf-8").replace("\r\n", "\n").replace("\n ", "")
    return dict(line.split(": ", 1) for line in text.split("\n\n", 1)[0].splitlines() if ": " in line)


def validate(jar: Path, apk: Path, aapt2: Path, expected: dict[str, str]) -> dict[str, str]:
    attrs = manifest(jar)
    pairs = {"package": "Grimoire-Source-Package", "sourceClass": "Grimoire-Source-Class",
             "versionCode": "Grimoire-Version-Code", "versionName": "Grimoire-Version-Name",
             "sourceVersionCode": "Grimoire-Source-Version-Code"}
    for key, header in pairs.items():
        if attrs.get(header) != expected[key]:
            raise ValueError(f"JAR {header} differs from extension.properties")
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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api", help="API checkout containing extensions-core (opt-in composite)")
    parser.add_argument("--aapt2", help="Android build-tools aapt2 binary")
    parser.add_argument("--variant", choices=("debug", "release"), default="debug")
    parser.add_argument("--verify-only", action="store_true", help="Verify artifacts already built at the standard paths")
    args = parser.parse_args()
    expected = properties(ROOT / "src/en/royalroad/extension.properties")
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    options = ["--no-build-cache", "--console=plain"]
    if args.api:
        options.append(f"-PgrimoireApiDir={Path(args.api).resolve().as_posix()}")
    if not args.verify_only:
        variant = args.variant.capitalize()
        subprocess.run([str(wrapper), f":en-royalroad:assemble{variant}",
                        f":en-royalroad:test{variant}UnitTest", *options], cwd=ROOT, check=True)
        subprocess.run([str(wrapper), "-p", "jvm", ":royalroad:test", ":royalroad:jar", *options], cwd=ROOT, check=True)
    apk_files = list((ROOT / f"src/en/royalroad/build/outputs/apk/{args.variant}").glob("*.apk"))
    if len(apk_files) != 1:
        raise ValueError(f"Expected one {args.variant} APK; found {len(apk_files)}")
    apk = apk_files[0]
    jar = ROOT / f"jvm/royalroad/build/libs/{expected['package']}-{expected['versionName']}.jar"
    attrs = validate(jar, apk, find_aapt2(args.aapt2), expected)
    jvm_report = json.loads((ROOT / "jvm/royalroad/build/contract-jvm.json").read_text(encoding="utf-8"))
    contract = jvm_report["contract"]
    if jvm_report["jarSha256"] != hashlib.sha256(jar.read_bytes()).hexdigest():
        raise ValueError("JVM contract proof belongs to a different JAR")
    if contract["sourceVersionCode"] != expected["sourceVersionCode"] or contract["sourceClass"] != expected["sourceClass"]:
        raise ValueError("Packaged source annotation differs from artifact metadata")
    output = ROOT / "build/dual/royalroad"
    output.mkdir(parents=True, exist_ok=True)
    artifacts = {}
    for kind, path in (("android", apk), ("jvm", jar)):
        shutil.copy2(path, output / path.name)
        artifacts[kind] = {"file": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    report = {"format": 1, "identity": expected, "sourceId": contract["sourceId"],
              "jvmCoreApi": attrs["Grimoire-Core-Api"], "artifacts": artifacts,
              "androidRuntimeParity": "UNEXECUTED by this build command"}
    (output / "artifacts.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Verified APK/JAR identity and JVM source contract: {output}")


if __name__ == "__main__":
    main()

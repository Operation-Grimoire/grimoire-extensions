"""Verify the exact Royal Road APK/JAR pair before running native parser parity."""
import argparse
import hashlib
import json
from pathlib import Path

from build_dual import ROOT, find_aapt2, source_identity, validate


def prepare(jar: Path, apk: Path, jvm_report: Path, output: Path) -> None:
    expected = source_identity(ROOT / "src/en/royalroad")
    attrs = validate(jar, apk, find_aapt2(None), expected)
    report = json.loads(jvm_report.read_text(encoding="utf-8"))
    jar_hash = hashlib.sha256(jar.read_bytes()).hexdigest()
    if report["jarSha256"] != jar_hash:
        raise ValueError("JVM contract proof belongs to a different JAR")
    contract = report["contract"]
    if (contract["sourceClass"] != expected["sourceClass"] or
            contract["sourceVersionCode"] != expected["sourceVersionCode"]):
        raise ValueError("JVM contract identity differs from the APK/JAR declarations")
    evidence = {
        "format": 1, "identity": expected, "sourceId": contract["sourceId"],
        "jvmCoreApi": attrs["Grimoire-Core-Api"],
        "androidRuntimeParity": "PENDING native execution and comparison",
        "artifacts": {
            "android": {"file": apk.name, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()},
            "jvm": {"file": jar.name, "sha256": jar_hash},
        },
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--jvm", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.jar, args.apk, args.jvm, args.output)
    print("Verified exact APK/JAR identity, requirements, and JVM contract hash")


if __name__ == "__main__":
    main()

"""Compare native APK and JVM JAR contract results, tied to the built artifact identity."""
import argparse
import json
from pathlib import Path


def compare(android: dict, jvm_report: dict, artifacts: dict) -> None:
    level = android.get("androidApiLevel")
    if type(level) is not int or level <= 0:
        raise ValueError("Android API level must be a positive integer")
    jvm = jvm_report["contract"]
    if android["contract"] != jvm:
        fields = sorted(key for key in android["contract"].keys() | jvm.keys()
                        if android["contract"].get(key) != jvm.get(key))
        raise ValueError(f"Android/JVM source contract differs: {fields}")
    identity = artifacts["identity"]
    checks = (
        (android["apkSha256"], artifacts["artifacts"]["android"]["sha256"], "APK hash"),
        (jvm_report["jarSha256"], artifacts["artifacts"]["jvm"]["sha256"], "JAR hash"),
        (android["apkPackage"], identity["package"], "APK package"),
        (str(android["apkVersionCode"]), identity["versionCode"], "APK versionCode"),
        (android["apkVersionName"], identity["versionName"], "APK versionName"),
        (android.get("apkCoreApi"), artifacts["jvmCoreApi"], "minimum host API"),
        (jvm["sourceId"], artifacts["sourceId"], "source ID"),
        (jvm["sourceClass"], identity["sourceClass"], "source entry point"),
        (jvm["sourceVersionCode"], identity["sourceVersionCode"], "source version"),
    )
    for actual, expected, label in checks:
        if actual != expected:
            raise ValueError(f"Contract proof belongs to a different {label}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android", required=True, type=Path)
    parser.add_argument("--jvm", required=True, type=Path)
    parser.add_argument("--artifacts", required=True, type=Path)
    parser.add_argument("--output", type=Path, help="Write a PASS record only after the exact artifact comparison succeeds")
    args = parser.parse_args()
    read = lambda path: json.loads(path.read_text(encoding="utf-8-sig"))
    android, jvm, artifacts = read(args.android), read(args.jvm), read(args.artifacts)
    compare(android, jvm, artifacts)
    if args.output:
        args.output.write_text(json.dumps({
            "status": "PASS", "identity": artifacts["identity"],
            "apkSha256": android["apkSha256"], "jarSha256": jvm["jarSha256"],
            "minimumHostApi": android["apkCoreApi"],
            "androidApiLevel": android["androidApiLevel"],
            "sourceId": jvm["contract"]["sourceId"],
            "pagesSha256": jvm["contract"]["pagesSha256"],
        }, indent=2) + "\n", encoding="utf-8")
    print("PASS: exact APK identity and Android/JVM source contract match")


if __name__ == "__main__":
    main()

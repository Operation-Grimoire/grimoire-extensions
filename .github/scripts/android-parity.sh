#!/usr/bin/env bash
set -euo pipefail
cd build/parity
modules=(en-royalroad ar-galaxynovels)
for module in "${modules[@]}"; do rm -f "$module/parity-result.json" "$module/comparison.log"; done
sha256sum --check sha256sums.txt
for apk in host.apk tests.apk en-royalroad/extension.apk ar-galaxynovels/extension.apk; do adb install -r -t "$apk"; done
adb shell am instrument -w -r \
  io.grimoire.extensions.parity.test/androidx.test.runner.AndroidJUnitRunner | tee instrumentation.log
grep -Fq 'OK (2 tests)' instrumentation.log
grep -Fq 'INSTRUMENTATION_CODE: -1' instrumentation.log
test "$(grep -Ec '^INSTRUMENTATION_STATUS_CODE: 0[[:space:]]*$' instrumentation.log)" -eq 2
if grep -Eq 'FAILURES|INSTRUMENTATION_(FAILED|ABORTED)|INSTRUMENTATION_STATUS_CODE: -[234]' instrumentation.log; then exit 1; fi
for module in "${modules[@]}"; do
  adb exec-out run-as io.grimoire.extensions.parity cat "files/contract-$module.json" > "$module/contract-android.json"
  python ../../scripts/compare_contracts.py --android "$module/contract-android.json" --jvm "$module/contract-jvm.json" --artifacts "$module/artifacts.json" --output "$module/parity-result.json" | tee "$module/comparison.log"
done

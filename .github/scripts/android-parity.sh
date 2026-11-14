#!/usr/bin/env bash
set -euo pipefail
cd build/parity
rm -f parity-result.json comparison.log
sha256sum --check sha256sums.txt
for apk in host.apk tests.apk royalroad.apk; do adb install -r -t "$apk"; done
adb shell am instrument -w -r \
  io.grimoire.extensions.parity.test/androidx.test.runner.AndroidJUnitRunner | tee instrumentation.log
grep -Fq 'OK (1 test)' instrumentation.log
grep -Fq 'INSTRUMENTATION_CODE: -1' instrumentation.log
test "$(grep -Ec '^INSTRUMENTATION_STATUS_CODE: 0[[:space:]]*$' instrumentation.log)" -eq 1
if grep -Eq 'FAILURES|INSTRUMENTATION_(FAILED|ABORTED)|INSTRUMENTATION_STATUS_CODE: -[234]' instrumentation.log; then exit 1; fi
adb exec-out run-as io.grimoire.extensions.parity cat files/contract-android.json > contract-android.json
python ../../scripts/compare_contracts.py --android contract-android.json --jvm contract-jvm.json --artifacts artifacts.json --output parity-result.json | tee comparison.log

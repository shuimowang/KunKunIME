#!/usr/bin/env bash
# Install this build and exercise its real IME on a dedicated, running emulator.
# Usage: smoke-test.sh [debug|release]. Requires the matching app/test APK pair.
# Each run starts with clean app data on a dedicated emulator. Reports are kept
# separately; only debug can cover the deterministic startup queue gate.
set -euo pipefail

smoke_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
app_id="io.github.shuimowang.kunkunime"
smoke_variant="${1:-debug}"
case "$smoke_variant" in
    debug|release) ;;
    *) echo 'Usage: smoke-test.sh [debug|release]' >&2; exit 2 ;;
esac
smoke_apk="$(python3 - "$smoke_root" "$smoke_variant" <<'PY'
import json
from pathlib import Path
import sys

folder = Path(sys.argv[1]) / "app/build/outputs/apk" / sys.argv[2]
metadata = json.loads((folder / "output-metadata.json").read_text())
outputs = [entry["outputFile"] for entry in metadata["elements"]
           if {f["value"] for f in entry["filters"] if f["filterType"] == "ABI"} == {"x86_64"}]
if len(outputs) != 1:
    raise SystemExit("Expected one x86_64 APK in this build's output metadata")
print(folder / outputs[0])
PY
)"
smoke_test_apk="$smoke_root/app/build/outputs/apk/androidTest/$smoke_variant/app-$smoke_variant-androidTest.apk"
smoke_results="$smoke_root/app/build/smoke-results/$smoke_variant"
smoke_adb="${ADB:-adb}"
smoke_serial="${ANDROID_SERIAL:-$($smoke_adb get-serialno)}"
adb_device() { "$smoke_adb" -s "$smoke_serial" "$@"; }

test -s "$smoke_apk"
test -s "$smoke_test_apk"
test "$(adb_device shell getprop ro.kernel.qemu | tr -d '\r')" = '1' || {
    echo 'This test changes the active input method; use a dedicated Android emulator.' >&2
    exit 1
}
test "$(adb_device shell getprop ro.product.cpu.abi | tr -d '\r')" = 'x86_64' || {
    echo 'This smoke test installs the x86_64 APK; use the matching dedicated emulator.' >&2
    exit 1
}
mkdir -p "$smoke_results"
old_ime="$(adb_device shell settings get secure default_input_method | tr -d '\r')"
old_hardware_ime="$(adb_device shell settings get secure show_ime_with_hard_keyboard | tr -d '\r')"

collect_results() {
    local status=$?
    trap - EXIT
    adb_device logcat -d -v threadtime > "$smoke_results/logcat.txt" 2>&1 || true
    adb_device shell dumpsys input_method > "$smoke_results/input-method.txt" 2>&1 || true
    adb_device exec-out screencap -p > "$smoke_results/screenshot.png" 2>/dev/null || true
    adb_device pull "/sdcard/Android/data/$app_id/files/smoke/." "$smoke_results/" >/dev/null 2>&1 || true
    if [[ -n "$old_ime" && "$old_ime" != null ]]; then
        adb_device shell ime set "$old_ime" >/dev/null 2>&1 || true
    fi
    if [[ "$old_hardware_ime" == null ]]; then
        adb_device shell settings delete secure show_ime_with_hard_keyboard >/dev/null 2>&1 || true
    else
        adb_device shell settings put secure show_ime_with_hard_keyboard "$old_hardware_ime" >/dev/null 2>&1 || true
    fi
    exit "$status"
}
trap collect_results EXIT

verify_instrumentation() {
    python3 - "$1" "$2" <<'PY'
from pathlib import Path
import re
import sys

result = Path(sys.argv[1]).read_text()
expected = int(sys.argv[2])
if not re.search(rf"^OK \({expected} tests?\)\s*$", result, re.MULTILINE):
    raise SystemExit(f"Instrumentation did not report {expected} passing tests: {sys.argv[1]}")
if any(marker in result for marker in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed")):
    raise SystemExit("Android reported an instrumentation failure")
PY
}

adb_device install -r -t "$smoke_apk"
adb_device install -r -t "$smoke_test_apk"
# Debug finishes in English mode; release must not inherit its settings, words,
# screenshots or derived caches. This guard-protected emulator is test-only.
test "$(adb_device shell pm clear "$app_id" | tr -d '\r')" = 'Success'
adb_device shell am force-stop "$app_id"
adb_device logcat -c

if [[ "$smoke_variant" == debug ]]; then
    # Direct view APIs and the startup-control API are only kept in debug.
    adb_device shell am instrument -w -r \
        -e class com.windinput.ime.keyboard.A11yActionTest,com.windinput.ime.keyboard.MultiTouchTest \
        "$app_id.test/androidx.test.runner.AndroidJUnitRunner" \
        | tr -d '\r' | tee "$smoke_results/keyboard-instrumentation.txt"
    verify_instrumentation "$smoke_results/keyboard-instrumentation.txt" 6
    adb_device logcat -d -v threadtime > "$smoke_results/keyboard-logcat.txt"
fi

# Keep the real-IME startup check cold after the isolated keyboard tests.
adb_device shell am force-stop "$app_id"
adb_device logcat -c

# am instrument can exit 0 even when a JUnit assertion failed. Require exactly
# the selected real-IME test to complete successfully, not only shell success.
adb_device shell am instrument -w -r \
    -e class com.windinput.ime.smoke.RealImeSmokeTest \
    "$app_id.test/androidx.test.runner.AndroidJUnitRunner" \
    | tr -d '\r' | tee "$smoke_results/instrumentation.txt"

verify_instrumentation "$smoke_results/instrumentation.txt" 1

smoke_screenshots=(pinyin-candidates.png number-pad.png)
if [[ "$smoke_variant" == debug ]]; then smoke_screenshots+=(cold-start-queued.png); fi
for screenshot in "${smoke_screenshots[@]}"; do
    adb_device pull "/sdcard/Android/data/$app_id/files/smoke/$screenshot" "$smoke_results/$screenshot"
    test -s "$smoke_results/$screenshot"
done

if [[ "$smoke_variant" == debug ]]; then
    echo 'KunKunIME debug smoke passed: startup queue/cancellation, six view tests, real IME input.'
else
    echo 'KunKunIME release smoke passed: non-debuggable app, real Pinyin/backspace, English, password, numbers, hide/show. No startup gate was used.'
fi

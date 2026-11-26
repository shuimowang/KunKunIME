#!/usr/bin/env bash
# Install this build and exercise its real IME on a dedicated, running emulator.
# Requires assembleDebug + assembleDebugAndroidTest; never reuses an old APK.
# Outputs instrumentation results, logcat and a screenshot under app/build/.
set -euo pipefail

smoke_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
app_id="io.github.shuimowang.kunkunime"
smoke_apk="$smoke_root/app/build/outputs/apk/debug/app-x86_64-debug.apk"
smoke_test_apk="$smoke_root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
smoke_results="$smoke_root/app/build/smoke-results"
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

adb_device install -r -t "$smoke_apk"
adb_device install -r -t "$smoke_test_apk"
adb_device shell am force-stop "$app_id"
adb_device logcat -c

# am instrument can exit 0 even when a JUnit assertion failed. Require exactly
# the selected real-IME test to complete successfully, not only shell success.
adb_device shell am instrument -w -r \
    -e class com.windinput.ime.smoke.RealImeSmokeTest \
    "$app_id.test/androidx.test.runner.AndroidJUnitRunner" \
    | tr -d '\r' | tee "$smoke_results/instrumentation.txt"

python3 - "$smoke_results/instrumentation.txt" <<'PY'
from pathlib import Path
import re
import sys

result = Path(sys.argv[1]).read_text()
if not re.search(r"^OK \(1 test\)\s*$", result, re.MULTILINE):
    raise SystemExit("Real IME instrumentation did not report one passing test")
if any(marker in result for marker in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed")):
    raise SystemExit("Android reported an instrumentation failure")
PY

for screenshot in cold-start-queued.png pinyin-candidates.png number-pad.png; do
    adb_device pull "/sdcard/Android/data/$app_id/files/smoke/$screenshot" "$smoke_results/$screenshot"
    test -s "$smoke_results/$screenshot"
done

echo 'KunKunIME device smoke passed: queued startup Pinyin, editor changes, English, password, numbers, hide/show.'

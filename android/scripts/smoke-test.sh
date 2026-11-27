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
old_recognizer="$(adb_device shell settings get secure voice_recognition_service | tr -d '\r')"
voice_probe_configured=false
smoke_stage="typing"
old_app_microphone=false
old_test_microphone=false

collect_results() {
    local status=$?
    trap - EXIT
    adb_device logcat -d -v threadtime > "$smoke_results/logcat.txt" 2>&1 || true
    adb_device shell dumpsys input_method > "$smoke_results/input-method.txt" 2>&1 || true
    adb_device exec-out screencap -p > "$smoke_results/screenshot.png" 2>/dev/null || true
    adb_device pull "/sdcard/Android/data/$app_id/files/smoke/." "$smoke_results/" >/dev/null 2>&1 || true
    if [[ "$voice_probe_configured" == true ]]; then
        # A later offline run clears logcat; keep each stage's own evidence.
        cp "$smoke_results/logcat.txt" "$smoke_results/$smoke_stage-logcat.txt" || true
        if [[ -z "$old_recognizer" || "$old_recognizer" == null ]]; then
            adb_device shell settings delete secure voice_recognition_service >/dev/null 2>&1 || true
        else
            adb_device shell settings put secure voice_recognition_service "$old_recognizer" >/dev/null 2>&1 || true
        fi
        # Revocation can kill the instrumented app, so restore only after the
        # runner exits. The test itself restores its temporary consent prefs.
        if [[ "$old_app_microphone" != true ]]; then
            adb_device shell pm revoke "$app_id" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
        fi
        if [[ "$old_test_microphone" != true ]]; then
            adb_device shell pm revoke "$app_id.test" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
        fi
    fi
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
        -e class com.windinput.ime.keyboard.A11yActionTest,com.windinput.ime.keyboard.MultiTouchTest,com.windinput.ime.keyboard.VoiceGestureTest \
        "$app_id.test/androidx.test.runner.AndroidJUnitRunner" \
        | tr -d '\r' | tee "$smoke_results/keyboard-instrumentation.txt"
    verify_instrumentation "$smoke_results/keyboard-instrumentation.txt" 18
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
adb_device logcat -d -v threadtime > "$smoke_results/typing-logcat.txt"

smoke_screenshots=(pinyin-candidates.png number-pad.png quick-phrases.png app-update.png)
if [[ "$smoke_variant" == debug ]]; then smoke_screenshots+=(cold-start-queued.png); fi
for screenshot in "${smoke_screenshots[@]}"; do
    adb_device pull "/sdcard/Android/data/$app_id/files/smoke/$screenshot" "$smoke_results/$screenshot"
    test -s "$smoke_results/$screenshot"
done

# Android's real SpeechRecognizer binds a provider from the test APK. This
# deterministic fixture captures no audio and is never packaged in the app.
# Both builds exercise real touch, IME/editor wiring and cross-process callbacks.
microphone_granted() {
    adb_device shell dumpsys package "$1" | python3 -c '
import re, sys
print("true" if re.search(r"android\.permission\.RECORD_AUDIO: granted=true\b", sys.stdin.read()) else "false")
'
}
old_app_microphone="$(microphone_granted "$app_id")"
old_test_microphone="$(microphone_granted "$app_id.test")"
voice_probe_configured=true
smoke_stage="voice-ipc"
adb_device shell settings put secure voice_recognition_service \
    "$app_id.test/com.windinput.ime.smoke.ControlledRecognitionService"
adb_device shell pm grant "$app_id" android.permission.RECORD_AUDIO
adb_device shell pm grant "$app_id.test" android.permission.RECORD_AUDIO
adb_device shell am force-stop "$app_id"
adb_device logcat -c
adb_device shell am instrument -w -r \
    -e class com.windinput.ime.smoke.VoiceImeSmokeTest \
    "$app_id.test/androidx.test.runner.AndroidJUnitRunner" \
    | tr -d '\r' | tee "$smoke_results/voice-ipc-instrumentation.txt"
verify_instrumentation "$smoke_results/voice-ipc-instrumentation.txt" 1
adb_device logcat -d -v threadtime > "$smoke_results/voice-ipc-logcat.txt"
for screenshot in voice-ipc-listening.png voice-ipc-final.png voice-ipc-cancelled.png; do
    adb_device pull "/sdcard/Android/data/$app_id/files/smoke/$screenshot" "$smoke_results/$screenshot"
    test -s "$smoke_results/$screenshot"
done

# Only the instrumentation APK contains the model and public sample recordings.
# This independent cold process validates real CPU/JNI Chinese/English inference,
# then real local AudioRecord start/cancellation through the IME. Keep the system
# fixture configured so the test can prove the local path never called it.
smoke_stage="offline-voice"
adb_device shell am force-stop "$app_id"
adb_device logcat -c
adb_device shell am instrument -w -r \
    -e class com.windinput.ime.smoke.OfflineVoiceSmokeTest \
    "$app_id.test/androidx.test.runner.AndroidJUnitRunner" \
    | tr -d '\r' | tee "$smoke_results/offline-voice-instrumentation.txt"
verify_instrumentation "$smoke_results/offline-voice-instrumentation.txt" 1
adb_device logcat -d -v threadtime > "$smoke_results/offline-voice-logcat.txt"
for result in offline-model-ready.png offline-voice-listening.png offline-voice-cancelled.png offline-native-results.txt; do
    adb_device pull "/sdcard/Android/data/$app_id/files/smoke/$result" "$smoke_results/$result"
    test -s "$smoke_results/$result"
done

if [[ "$smoke_variant" == debug ]]; then
    echo 'KunKunIME debug smoke passed: startup queue/cancellation, eighteen view tests, real IME/phrases, controlled speech IPC, real native Chinese/English samples and local AudioRecord cancellation. Physical microphone accuracy not measured.'
else
    echo 'KunKunIME release smoke passed: non-debuggable app, real IME/phrases, controlled speech IPC, real native Chinese/English samples and local AudioRecord cancellation. No startup gate; physical microphone accuracy not measured.'
fi

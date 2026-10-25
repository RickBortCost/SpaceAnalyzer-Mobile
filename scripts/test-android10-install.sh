#!/usr/bin/env bash
# Tests the exact APK uploaded to users, without rebuilding or re-signing it.
set -euo pipefail
apk=app/build/outputs/apk/debug/app-debug.apk
report=app/build/reports/android10-install
package=it.spaceanalyzer.mobile
mkdir -p "$report"
collect() {
    adb logcat -d > "$report/logcat.txt" 2>&1 || true
    adb shell dumpsys package "$package" > "$report/package.txt" 2>&1 || true
    adb exec-out screencap -p > "$report/screenshot.png" 2>/dev/null || true
}
trap collect EXIT
adb wait-for-device
[[ "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" == 29 ]]
adb logcat -c
sha256sum "$apk" > "$report/apk.sha256"
adb install -r "$apk" | tee "$report/install.txt"
grep -q '^Success' "$report/install.txt"
adb shell am start -W -n "$package/.MainActivity" | tee "$report/launch.txt"
grep -q 'Status: ok' "$report/launch.txt"
# Allow Compose to finish the first frame and catch early startup crashes.
sleep 5
adb shell pidof "$package" | tee "$report/pid.txt"
test -s "$report/pid.txt"
adb shell uiautomator dump /sdcard/space-analyzer-ui.xml
adb pull /sdcard/space-analyzer-ui.xml "$report/ui.xml"
python3 - "$report/ui.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
text = ' '.join(n.attrib.get('text', '') for n in root.iter())
assert 'Space Analyzer Mobile' in text, text
assert 'SOLA LETTURA' in text, text
print('Android 10: APK installed and main screen rendered successfully.')
PY

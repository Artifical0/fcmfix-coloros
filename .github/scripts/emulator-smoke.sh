#!/usr/bin/env bash
# Installs the R8-shrunk release APK on the CI emulator, opens every page and screenshots it,
# and fails if the app crashes. LSPosed is not installed there, so the app shows the inactive
# module state, and exporting fails for lack of root: both are expected.
set -euo pipefail
APK=$1
OUT=${2:-emulator-shots}
PKG=io.github.artifical0.fcmfix.coloros
HERE=$(dirname "$0")
mkdir -p "$OUT"

shot() { adb exec-out screencap -p > "$OUT/$1.png"; }

tap() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb pull /sdcard/ui.xml "$OUT/ui-$1.xml" > /dev/null
  local point
  point=$(python3 "$HERE/ui_tap.py" "$OUT/ui-$1.xml" "$1")
  adb shell input tap $point
  sleep 2
}

check() {
  if ! adb shell pidof "$PKG" > /dev/null; then
    echo "::error::FCMFix is not running after: $1"
    adb logcat -d -b crash
    exit 1
  fi
}

adb install -r "$APK"
adb logcat -c
adb shell am start -W -n "$PKG/com.kooritea.fcmfix.MainActivity"
# The app list scan and the wait for LSPosed (3 s) both have to finish.
sleep 10
shot 01_status; check launch

tap 复制结果; shot 02_copied; check copy
tap 导出完整报告; sleep 3; shot 03_export_failed; check export
tap 确定

tap 应用; sleep 2; shot 04_apps; check apps
tap 搜索
adb shell input text com.android.settings
sleep 2; shot 05_search
tap com.android.settings; sleep 2; shot 06_app_detail; check detail
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 1

tap 时间线; shot 07_timeline; check timeline
tap 设置; shot 08_settings; check settings
adb shell input keyevent KEYCODE_BACK; sleep 1
shot 09_back; check back

# Recreate the activity (as after a theme or font change) and come back.
adb shell cmd uimode night yes; sleep 3; shot 10_night; check night
adb shell cmd uimode night no; sleep 2

if adb logcat -d -b crash | grep -q "$PKG"; then
  echo "::error::FCMFix crashed"
  adb logcat -d -b crash
  exit 1
fi
adb logcat -d > "$OUT/logcat.txt"
echo "smoke test passed"

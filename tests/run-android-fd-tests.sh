#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
serial=$(adb get-serialno)
case "$serial" in emulator-*) ;; *) echo 'Only a disposable CI emulator is supported; refusing physical device' >&2; exit 64;; esac
export ANDROID_SERIAL="$serial"
NDK="${ANDROID_HOME:?}/ndk/27.2.12479018"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android35-clang" \
  -Wall -Wextra -Werror -O2 tests/fake-tailscale.c -o tests/tailscale-android
REMOTE="/data/local/tmp/tailcontrol-fd-ci-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}"
mkdir -p test-reports
adb root
adb wait-for-device
[[ "$(adb shell id -u | tr -d '\r')" == 0 ]]
adb shell mkdir -p "$REMOTE"
# Preserve isolated fixture logs even when the remote test fails.
collect() {
  adb pull "$REMOTE" test-reports/android-fixture >/dev/null 2>&1 || true
  adb shell rm -rf "$REMOTE" || true
}
trap collect EXIT
adb push app/src/main/assets/taildrop-owner.sh "$REMOTE/taildrop-owner.sh"
adb push tests/tailscale-android "$REMOTE/tailscale"
adb push tests/android-fd-smoke.sh "$REMOTE/android-fd-smoke.sh"
adb shell chmod 700 "$REMOTE/tailscale"
adb shell /system/bin/sh "$REMOTE/android-fd-smoke.sh" "$REMOTE" 2>&1 | tee test-reports/android-mksh-fd.txt

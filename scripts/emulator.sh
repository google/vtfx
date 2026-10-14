#!/bin/sh
# Copyright 2026 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Manages the Android emulator for end-to-end tests. See docs/design/testing.md section 8.
#
# Usage:
#   scripts/emulator.sh setup   Installs the system image and creates the AVD, if missing.
#   scripts/emulator.sh start   Boots the AVD without a window and waits until it is ready.
#   scripts/emulator.sh stop    Shuts the emulator down.
#
# Requires KVM. ANDROID_HOME defaults to ~/Android/Sdk.
#
# The emulator listens on a fixed port, so that its serial (emulator-5580) is known even when other
# emulators run, e.g. the vtfx-probe AVD of tools/ime-probe. scripts/e2e_ime.py uses that serial.

set -eu

sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
avd=vtfx-e2e
# The Play Store image ships Gboard, which the IME tests need.
image="system-images;android-37.0;google_apis_playstore;x86_64"
port=5580
serial="emulator-$port"

adb() {
  "$sdk/platform-tools/adb" -s "$serial" "$@"
}

case "${1:-}" in
  setup)
    "$sdk/cmdline-tools/latest/bin/sdkmanager" --install emulator "$image"
    if ! "$sdk/emulator/emulator" -list-avds | grep -qx "$avd"; then
      echo no | "$sdk/cmdline-tools/latest/bin/avdmanager" create avd -n "$avd" -k "$image" -d pixel_7
    fi
    ;;
  start)
    log=${TMPDIR:-/tmp}/vtfx-emulator.log
    "$sdk/emulator/emulator" -avd "$avd" -port "$port" -no-window -no-audio -no-boot-anim \
      -gpu swiftshader_indirect -no-snapshot-save > "$log" 2>&1 &
    adb wait-for-device
    until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
      sleep 2
    done
    # Show the software keyboard even if a hardware keyboard is reported.
    adb shell settings put secure show_ime_with_hard_keyboard 1
    echo "Emulator $serial is ready. Log: $log"
    ;;
  stop)
    adb emu kill
    ;;
  *)
    sed -n '2,12p' "$0" >&2
    exit 1
    ;;
esac

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

# Records an IME probe trace while a person types on a real device.
#
# Usage: record_manual.sh OUT_DIR NAME [am start extras ...]
# Example: record_manual.sh traces/gboard-pixel en@typical --es inputType text_typical --ez noLearning false
#
# Launches the probe with the given extras, records until Enter is pressed in this terminal, and
# writes OUT_DIR/NAME.jsonl. Set ANDROID_SERIAL to choose a device when several are connected.

set -eu

if [ $# -lt 2 ]; then
  sed -n '2,9p' "$0" >&2
  exit 1
fi
out_dir=$1
name=$2
shift 2

mkdir -p "$out_dir"
if [ -e "$out_dir/$name.jsonl" ]; then
  echo "$out_dir/$name.jsonl already exists. Choose another NAME or remove it." >&2
  exit 1
fi
adb shell am start -S -W -n vtfx.tools.imeprobe/.MainActivity --es label "$name" "$@" >/dev/null
adb logcat -c
adb logcat -s VtfxImeProbe:I -v raw > "$out_dir/$name.raw" &
logcat_pid=$!

echo "Recording '$name'. Tap the dark area to show the keyboard, type, then press Enter here."
read -r _
kill "$logcat_pid"
wait "$logcat_pid" 2>/dev/null || true

grep '^{' "$out_dir/$name.raw" > "$out_dir/$name.jsonl" || true
rm "$out_dir/$name.raw"
echo "Wrote $out_dir/$name.jsonl ($(wc -l < "$out_dir/$name.jsonl") events)"

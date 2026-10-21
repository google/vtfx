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

# Fuzzes the fuzz targets for longer than scripts/check.sh does (docs/design/testing.md section 7),
# and appends the results to build/reports/fuzzing-log.tsv.
#
# Usage: scripts/fuzz.sh [DURATION [MODULE...]]
#   DURATION is per fuzz target, a number of seconds, minutes, or hours (e.g. 30s, 30m, 2h). The
#   default is 10m.
#   MODULEs are the modules to fuzz, one after another. The default is all modules with a fuzz
#   target.
#
# A crashing input is written to the corpus directory of the fuzz target,
# src/fuzz/corpus/<target>/, where the regular test run replays it. Fix the bug, keep the input,
# and add a unit test for it.

set -eu

cd "$(dirname "$0")/.."

duration="${1:-10m}"
[ "$#" -gt 0 ] && shift
modules="${*:-vtfx-core vtfx-gate vtfx-input vtfx-font vtfx-font-gate}"
log="build/reports/fuzzing-log.tsv"
output="$(mktemp)"
trap 'rm -f "$output"' EXIT

# The fuzz target of each module, as named in the log.
target_of() {
  case "$1" in
    vtfx-core) echo "vtfx-core:TerminalFuzzer" ;;
    vtfx-gate) echo "vtfx-gate:GateFuzzer" ;;
    vtfx-input) echo "vtfx-input:ImeFuzzer" ;;
    vtfx-font) echo "vtfx-font:FontFuzzer" ;;
    vtfx-font-gate) echo "vtfx-font-gate:VerifierFuzzer" ;;
    *) echo "No fuzz target in module $1." >&2; exit 2 ;;
  esac
}

mkdir -p "$(dirname "$log")"
if [ ! -f "$log" ]; then
  printf 'date\tcommit\ttarget\truns\tseconds\tresult\n' >"$log"
fi

overall=0
for module in $modules; do
  target="$(target_of "$module")"
  status=0
  ./gradlew --console=plain --info ":$module:fuzz" "-Pvtfx.fuzzDuration=$duration" >"$output" 2>&1 || status=$?

  runs="$(sed -n 's/^Done \([0-9]*\) runs in \([0-9]*\) second.*/\1 \2/p' "$output")"
  if [ -z "$runs" ]; then
    tail -n 50 "$output"
    echo "No fuzzing statistics found for $target." >&2
    exit 1
  fi
  result="pass"
  if [ "$status" -ne 0 ]; then
    result="fail"
    overall=1
    grep -E 'ERROR|Exception|artifact_prefix|FAILED' "$output" | head -n 40
  fi

  echo "$runs" | while read -r count seconds; do
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(git rev-parse --short HEAD)" \
      "$target" "$count" "$seconds" "$result" >>"$log"
  done
  tail -n 1 "$log"
done
exit "$overall"

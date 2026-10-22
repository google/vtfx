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

# Runs all gating checks. CI runs this script too, so that local and CI results agree.
# See docs/design/testing.md section 9.
#
# Usage: scripts/check.sh

set -eu

cd "$(dirname "$0")/.."

echo "== KDoc of the public API"
python3 -I scripts/check_kdoc.py

echo "== References to documents"
python3 -I scripts/check_doc_links.py

echo "== License headers"
# Files that are not Google's own source code have no header (docs/decisions/0018). To add missing
# headers, run the same command with `-c "Google LLC" -l apache` instead of `-check`. The version
# of addlicense and the checksums of its modules are pinned in tools/addlicense/go.mod and go.sum,
# and -mod=readonly keeps the build from changing them.
go build -C tools/addlicense -mod=readonly -o "$(pwd)/build/tools/addlicense" github.com/google/addlicense
git ls-files -z | xargs -0 build/tools/addlicense -check \
    -ignore 'spec/**' \
    -ignore 'tools/ime-probe/traces/**' \
    -ignore 'third_party/**'

echo "== Script tests"
python3 -I -m unittest discover -s scripts/tests

echo "== Generated sources"
# The character width tables and the default-ignorable code points are checked in
# (docs/decisions/0029). Fails if they are not what the UCD files in third_party/unicode generate.
python3 -I scripts/generate_unicode_tables.py --check

echo "== Gradle wrapper"
# gradle-wrapper.jar is the first code that ./gradlew runs, before distributionSha256Sum and
# dependency verification apply, and a binary that a review of the diff cannot judge. This is the
# checksum that Gradle publishes for the jar of the version in gradle-wrapper.properties
# (https://services.gradle.org/distributions/gradle-<version>-wrapper.jar.sha256); update both
# together.
echo "3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98  gradle/wrapper/gradle-wrapper.jar" |
    sha256sum --check --strict -

echo "== Gradle build"
./gradlew --console=plain build

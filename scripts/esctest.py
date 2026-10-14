#!/usr/bin/env python3
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

"""Runs esctest2 against vtfx and writes a report.

esctest2 (https://github.com/ThomasDickey/esctest2) writes control sequences to its terminal and
reads the replies, including the screen contents through DECRQCRA. Here its terminal is a pseudo
terminal whose other side is EsctestHost, a JVM program in vtfx-gate's tests that runs vtfx-core
and vtfx-gate. The result is for reporting, not a gate (docs/design/testing.md section 11).

esctest2 is GPL-2.0 and is not part of this repository: it is fetched at a fixed commit into
.cache/esctest2.

Usage: esctest.py [--report PATH] [--include REGEX]

The reasons for the failing tests are in tools/esctest/reasons.toml. A failing test without a
reason is reported as unexplained.
"""

import argparse
import datetime
import os
import pty
import re
import select
import subprocess
import sys
import time
import tomllib

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ESCTEST_REPOSITORY = "https://github.com/ThomasDickey/esctest2.git"
ESCTEST_COMMIT = "2798f12149a19c3295e9b4853ab2da4b2eff1b2b"
ESCTEST_DIR = os.path.join(ROOT, ".cache", "esctest2")


def fetch_esctest():
    if not os.path.isdir(os.path.join(ESCTEST_DIR, ".git")):
        subprocess.run(["git", "clone", "-q", ESCTEST_REPOSITORY, ESCTEST_DIR], check=True)
    head = subprocess.run(["git", "-C", ESCTEST_DIR, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
    if head != ESCTEST_COMMIT:
        subprocess.run(["git", "-C", ESCTEST_DIR, "fetch", "-q", "origin"], check=True)
        subprocess.run(["git", "-C", ESCTEST_DIR, "checkout", "-q", ESCTEST_COMMIT], check=True)


def host_classpath():
    subprocess.run([os.path.join(ROOT, "gradlew"), "--console=plain", "-q", ":vtfx-gate:testClasses",
                    ":vtfx-gate:esctestClasspath"], cwd=ROOT, check=True)
    with open(os.path.join(ROOT, "vtfx-gate", "build", "esctest-classpath.txt")) as f:
        return f.read().strip()


def run(include, logfile):
    """Runs esctest with its stdin and stdout on a pseudo terminal connected to EsctestHost."""
    pty_fd, tty_fd = pty.openpty()
    host = subprocess.Popen(["java", "-cp", host_classpath(), "com.google.vtfx.gate.esctest.EsctestHost"],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    esctest = subprocess.Popen(
        [sys.executable, "esctest.py", "--expected-terminal=xterm", "--xterm-checksum=400", "--max-vt-level=5",
         "--timeout=2", "--no-print-logs", f"--include={include}", f"--logfile={logfile}"],
        cwd=os.path.join(ESCTEST_DIR, "esctest"), stdin=tty_fd, stdout=tty_fd, stderr=subprocess.DEVNULL)
    os.close(tty_fd)
    host_out = host.stdout.fileno()
    try:
        while esctest.poll() is None:
            ready, _, _ = select.select([pty_fd, host_out], [], [], 0.5)
            if pty_fd in ready:
                try:
                    data = os.read(pty_fd, 65536)
                except OSError:
                    break
                host.stdin.write(data)
                host.stdin.flush()
            if host_out in ready:
                data = os.read(host_out, 65536)
                if not data:
                    break
                os.write(pty_fd, data)
    finally:
        esctest.wait()
        host.stdin.close()
        host.wait()
        os.close(pty_fd)


def parse_log(logfile):
    """Returns {test: (status, detail)} from esctest's log."""
    results = {}
    current = None
    error = []
    with open(logfile, encoding="utf-8", errors="replace") as f:
        lines = f.read().splitlines()
    for line in lines:
        text = re.sub(r"^\S+ \S+ ", "", line) if re.match(r"^\d{4}-", line) else line
        match = re.search(r"Run test: (\S+)", text)
        if match:
            current = match.group(1)
            error = []
            continue
        if current is None:
            continue
        if "Passed." in text:
            results[current] = ("pass", "")
        elif "Fails as expected" in text:
            results[current] = ("known", text.split("Fails as expected:", 1)[1].strip())
        elif "Skipped because" in text:
            results[current] = ("skipped", text.split("Skipped because", 1)[1].strip())
        elif "FAILED" in text:
            results[current] = ("fail", "")
        elif results.get(current, ("",))[0] == "fail" and text.strip():
            # The exception, and for a checksum failure, its first location.
            error.append(text.strip())
            exception = next((i for i, e in enumerate(error) if e.startswith("esctypes.")), None)
            if exception is not None:
                results[current] = ("fail", " ".join(error[exception:exception + 2]))
    return results


def test_sources():
    """Returns {"<Class>.<test>": source of the test method} for esctest's tests."""
    sources = {}
    tests_dir = os.path.join(ESCTEST_DIR, "esctest", "tests")
    for name in sorted(os.listdir(tests_dir)):
        if not name.endswith(".py"):
            continue
        with open(os.path.join(tests_dir, name), encoding="utf-8") as f:
            lines = f.read().splitlines()
        cls = None
        current = None
        for line in lines:
            match = re.match(r"class (\w+)", line)
            if match:
                cls = match.group(1)
                current = None
                continue
            match = re.match(r"  def (test_\w+)", line)
            if match and cls:
                current = f"{cls}.{match.group(1)}"
                sources[current] = ""
                continue
            if current and (line.startswith("    ") or not line.strip()):
                sources[current] += line + "\n"
            elif current and not line.startswith("  @"):
                current = None
    return sources


def load_reasons():
    with open(os.path.join(ROOT, "tools", "esctest", "reasons.toml"), "rb") as f:
        return tomllib.load(f)["reason"]


def reason_for(test, reasons, sources):
    """Returns the first reason whose "tests" matches the name or "source" matches the source."""
    for reason in reasons:
        if "tests" in reason and re.search(reason["tests"], test):
            return reason
        if "source" in reason and re.search(reason["source"], sources.get(test, "")):
            return reason
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--report", default=os.path.join(ROOT, "build", "reports", "esctest.md"))
    parser.add_argument("--include", default=".*")
    args = parser.parse_args()

    fetch_esctest()
    logfile = os.path.join(ROOT, "build", "esctest.log")
    os.makedirs(os.path.dirname(logfile), exist_ok=True)
    started = time.time()
    run(args.include, logfile)
    results = parse_log(logfile)
    reasons = load_reasons()

    counts = {}
    for status, _ in results.values():
        counts[status] = counts.get(status, 0) + 1
    failing = sorted(name for name, (status, _) in results.items() if status == "fail")
    sources = test_sources()
    explained = [(name, reason_for(name, reasons, sources)) for name in failing]
    unexplained = [name for name, reason in explained if reason is None]

    commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    lines = [
        "# esctest2 results",
        "",
        "Generated by `scripts/esctest.py`. Do not edit by hand. The reasons for failures are in `tools/esctest/reasons.toml`. "
        "For reports, not a gate (section 11 of testing.md).",
        "",
        "| Item | Value |",
        "|---|---|",
        f"| Date (UTC) | {datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%d %H:%M')} |",
        f"| Commit | {commit} |",
        f"| esctest2 | {ESCTEST_COMMIT[:12]} (`--expected-terminal=xterm --max-vt-level=5`) |",
        f"| Duration | {int(time.time() - started)} seconds |",
        f"| Result | {counts.get('pass', 0)} passed, {counts.get('fail', 0)} failed ({len(unexplained)} without a reason), "
        f"{counts.get('known', 0)} excluded as known bugs, {counts.get('skipped', 0)} skipped for missing capabilities |",
        "",
    ]
    if unexplained:
        lines += ["## Failures without a reason", "", "| Test | Error |", "|---|---|"]
        for name in unexplained:
            detail = results[name][1].replace("|", "\\|")[:200]
            lines.append(f"| {name} | {detail} |")
        lines.append("")
    groups = {}
    for name, reason in explained:
        if reason is not None:
            groups.setdefault(reason["summary"], (reason, []))[1].append(name)
    if groups:
        lines += ["## Failures with a reason", ""]
        for summary, (reason, names) in sorted(groups.items()):
            lines += [f"### {summary}", "", f"{reason['kind']}. {reason.get('detail', '')}", ""]
            lines += [f"- {name}" for name in names]
            lines.append("")
    os.makedirs(os.path.dirname(args.report), exist_ok=True)
    with open(args.report, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"{counts}, unexplained {len(unexplained)} -> {args.report}")


if __name__ == "__main__":
    main()

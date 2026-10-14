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

"""Prints an IME probe trace (JSON lines) in a compact human-readable form.

Usage: summarize.py TRACE.jsonl
"""

import json
import sys


def main():
    for line in open(sys.argv[1], encoding="utf-8"):
        e = json.loads(line)
        ev = e["ev"]
        if ev == "ic":
            print("  " * e["depth"] + "IC " + e["call"] + " " + json.dumps(e["args"], ensure_ascii=False))
        elif ev == "ret":
            # Show the return value of queries, and the buffer state after top-level calls.
            if e["ret"] not in (True, False, None) and e["call"].startswith(("get", "deleteSurrounding")):
                print("  " * e["depth"] + "   ret " + json.dumps(e["ret"], ensure_ascii=False))
            if e["depth"] == 0:
                print("   state " + json.dumps(e["state"], ensure_ascii=False))
        elif ev == "send":
            print("SEND " + repr(e["bytes"]))
        elif ev == "key":
            print(f"KEY {'down' if e['action'] == 0 else 'up'} {e['keyCode']} composing={e['composing']}")
        else:
            rest = {k: v for k, v in e.items() if k not in ("seq", "t", "ev")}
            print(ev + " " + json.dumps(rest, ensure_ascii=False))


if __name__ == "__main__":
    main()

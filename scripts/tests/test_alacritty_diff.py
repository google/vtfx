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

"""Tests for scripts/alacritty_diff.py.

Run with: python3 -m unittest discover -s scripts/tests
"""

import importlib.util
import pathlib
import re
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "alacritty_diff.py"


def load_module():
    spec = importlib.util.spec_from_file_location("alacritty_diff", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


diff_mod = load_module()


class DecodeInputsTest(unittest.TestCase):
    def test_vtspec_string_escapes(self):
        raw = diff_mod.decode_vtspec_string(r'"\e[2;3H\x41\u3042\r\n\t\b\\\"end"')
        self.assertEqual(b'\x1b[2;3HA\xe3\x81\x82\r\n\t\b\\"end', raw)

    def test_load_vtspec_cases(self):
        with tempfile.TemporaryDirectory() as tmp:
            (pathlib.Path(tmp) / "sample.vtspec").write_text(
                "=== Move cursor\nsize 10x3\ninput \"\\e[2;3HX\"\nscreen\n  |          |\ncursor 4,2\n",
                encoding="utf-8",
            )
            cases = diff_mod.load_vtspec_cases(tmp, "spec")
            self.assertEqual(1, len(cases))
            self.assertEqual("spec/sample.vtspec:Move cursor", cases[0].case_id)
            self.assertEqual((10, 3), (cases[0].columns, cases[0].rows))
            self.assertEqual((diff_mod.WriteOp(b"\x1b[2;3HX"),), cases[0].ops)

    def test_terminal_fuzz_input_decoding_matches_fuzzer_format(self):
        # cols = 1 + 4 % 12 = 5, rows = 1 + 2 % 6 = 3, scrollback = 3 % 5 = 3
        # Write("ab"), split, Resize(1 + 14 % 12 = 3, 1 + (14 // 12) % 6 = 2), Write("c")
        raw = bytes([4, 2, 3]) + b"ab\xfe\xff\x0ec"
        case = diff_mod.decode_terminal_fuzz_input("fuzz-core/seed", "fuzz-core", raw)
        self.assertIsNotNone(case)
        self.assertEqual((5, 3, 3), (case.columns, case.rows, case.scrollback))
        self.assertEqual(
            (
                diff_mod.WriteOp(b"ab"),
                diff_mod.ResizeOp(3, 2),
                diff_mod.WriteOp(b"c"),
            ),
            case.ops,
        )

    def test_gate_fuzz_input_decoding_matches_fuzzer_format(self):
        # 7-byte header, then Write("hi"), split, resize to 1+9 x 1+4, Write("ok"), split, cell-size ignored + Write("z")
        raw = bytes([9, 3, 0, 0, 10, 0, 20]) + b"hi\xfe\xfd\x09\x04ok\xfe\xfc\x01\x02z"
        case = diff_mod.decode_gate_fuzz_input("fuzz-gate/seed", "fuzz-gate", raw)
        self.assertIsNotNone(case)
        self.assertEqual((10, 4), (case.columns, case.rows))
        self.assertEqual(
            (
                diff_mod.WriteOp(b"hi"),
                diff_mod.ResizeOp(10, 5),
                diff_mod.WriteOp(b"ok"),
                diff_mod.WriteOp(b"z"),
            ),
            case.ops,
        )


class ProtocolAndComparisonTest(unittest.TestCase):
    def test_case_and_result_round_trip(self):
        case = diff_mod.DiffCase(
            "generated/test",
            "generated",
            8,
            2,
            10,
            (diff_mod.WriteOp(b"ab"), diff_mod.ResizeOp(4, 2)),
        )
        encoded = diff_mod.format_case_line(case, include_styles=True)
        self.assertTrue(encoded.startswith("CASE\t"))
        self.assertIn("W:6162 R:4x2", encoded)

        ok_line = (
            f"OK\t{'generated/test'.encode().hex()}\t2,1\t"
            f"{'abcd'.encode().hex()},{'    '.encode().hex()}\t"
            f"{'d/d//;d/d//'.encode().hex()},{'d/d//;d/d//'.encode().hex()}\n"
        )
        cid, res = diff_mod.parse_result_line(ok_line)
        self.assertEqual("generated/test", cid)
        self.assertEqual("ok", res.status)
        self.assertEqual((2, 1), res.cursor)
        self.assertEqual(("abcd", "    "), res.lines)

        err_line = f"ERR\t{'generated/test'.encode().hex()}\t{'index out of bounds'.encode().hex()}\n"
        cid_err, res_err = diff_mod.parse_result_line(err_line)
        self.assertEqual("generated/test", cid_err)
        self.assertEqual("err", res_err.status)
        self.assertEqual("index out of bounds", res_err.error)

    def test_compare_results_detects_text_cursor_style_and_panic(self):
        base = diff_mod.EngineResult("ok", (1, 0), ("a   ", "    "), ("i1/d/B/;", "d/d//;"))
        self.assertIsNone(diff_mod.compare_results(base, base, include_styles=True))

        diff_text = diff_mod.EngineResult("ok", (1, 0), ("b   ", "    "), ("i1/d/B/;", "d/d//;"))
        self.assertIn("row 0", diff_mod.compare_results(base, diff_text))

        diff_cursor = diff_mod.EngineResult("ok", (2, 0), ("a   ", "    "), ("i1/d/B/;", "d/d//;"))
        self.assertIn("cursor", diff_mod.compare_results(base, diff_cursor))

        diff_style = diff_mod.EngineResult("ok", (1, 0), ("a   ", "    "), ("d/d//;", "d/d//;"))
        self.assertIsNone(diff_mod.compare_results(base, diff_style, include_styles=False))
        self.assertIn("style row 0", diff_mod.compare_results(base, diff_style, include_styles=True))

        panicked = diff_mod.EngineResult("err", error="panic")
        self.assertIn("alacritty=err(panic)", diff_mod.compare_results(base, panicked))

    def test_run_engine_recovers_after_hanging_case(self):
        helper = (
            "import sys, time\n"
            "for line in sys.stdin:\n"
            "    parts = line.rstrip('\\n').split('\\t')\n"
            "    cid = bytes.fromhex(parts[1]).decode()\n"
            "    if cid == 'hang':\n"
            "        time.sleep(5)\n"
            "    sys.stdout.write(f'OK\\t{parts[1]}\\t0,0\\t20\\t\\n')\n"
            "    sys.stdout.flush()\n"
        )
        cases = [
            diff_mod.DiffCase("ok-1", "gen", 1, 1, 0, (diff_mod.WriteOp(b"a"),)),
            diff_mod.DiffCase("hang", "gen", 1, 1, 0, (diff_mod.WriteOp(b"b"),)),
            diff_mod.DiffCase("ok-2", "gen", 1, 1, 0, (diff_mod.WriteOp(b"c"),)),
        ]
        results = diff_mod.run_engine([sys.executable, "-c", helper], cases, include_styles=False, case_timeout=0.15)
        self.assertEqual("ok", results["ok-1"].status)
        self.assertEqual("err", results["hang"].status)
        self.assertIn("timeout", results["hang"].error)
        self.assertEqual("ok", results["ok-2"].status)


class ReasonsAndGeneratedSuiteTest(unittest.TestCase):
    ALLOWED_KINDS = {
        "Bug in alacritty_terminal",
        "Open question",
        "Intentional difference",
        "Unsupported by alacritty_terminal",
    }

    def test_reasons_file_is_valid(self):
        reasons = diff_mod.load_reasons()
        self.assertGreaterEqual(len(reasons), 1)
        for entry in reasons:
            self.assertIn(entry["kind"], self.ALLOWED_KINDS)
            self.assertTrue(entry.get("summary"))
            self.assertTrue(any(k in entry for k in ("cases", "input", "difference")))
            for key in ("cases", "input", "difference"):
                if key in entry:
                    re.compile(entry[key])

    def test_generated_cases_are_deterministic_and_unique(self):
        first = diff_mod.generated_cases()
        second = diff_mod.generated_cases()
        self.assertEqual(first, second)
        ids = [c.case_id for c in first]
        self.assertEqual(len(ids), len(set(ids)))

    def test_render_report_highlights_unexplained_differences(self):
        case1 = diff_mod.DiffCase("generated/a", "generated", 4, 2, 10, (diff_mod.WriteOp(b"x"),))
        case2 = diff_mod.DiffCase("generated/b", "generated", 4, 2, 10, (diff_mod.WriteOp(b"y"),))
        reason = {"kind": "Bug in alacritty_terminal", "summary": "Known bug", "detail": "Details."}
        report = diff_mod.render_report(
            [case1, case2],
            [(case1, "cursor: vtfx=(0, 0) vs alacritty=(1, 0)", None), (case2, "row 0: 'a' vs 'b'", reason)],
            duration_sec=1.2,
            commit="abc1234",
            include_styles=False,
        )
        self.assertIn("2 differing (1 without a reason)", report)
        self.assertIn("## Differences without a reason", report)
        self.assertIn("## Bug in alacritty_terminal", report)


if __name__ == "__main__":
    unittest.main()

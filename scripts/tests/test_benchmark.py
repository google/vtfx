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

"""Tests for scripts/benchmark.py.

Run with: python3 -m unittest discover -s scripts/tests
"""

import importlib.util
import pathlib
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "benchmark.py"


def load_benchmark():
    spec = importlib.util.spec_from_file_location("benchmark", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


benchmark = load_benchmark()


def scene(frames):
    return {"frames": frames, "p50": 14.0, "drawP50": 6.0}


class CheckRendersTest(unittest.TestCase):
    def test_every_scene_with_frames_passes(self):
        benchmark.check_renders({"full": scene(299), "scroll": scene(299), "flood": scene(262)})

    def test_a_scene_without_frames_fails(self):
        # Behind the lock screen every scene reports 0 frames and times of 0 ms, which are below
        # every target.
        with self.assertRaises(SystemExit) as raised:
            benchmark.check_renders({"full": scene(0), "scroll": scene(0), "flood": scene(0)})
        self.assertIn("full, scroll, flood", str(raised.exception.code))

    def test_a_missing_scene_fails(self):
        with self.assertRaises(SystemExit) as raised:
            benchmark.check_renders({"full": scene(299), "scroll": scene(299)})
        self.assertIn("flood", str(raised.exception.code))


if __name__ == "__main__":
    unittest.main()

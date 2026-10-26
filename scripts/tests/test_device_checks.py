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

"""Tests for scripts/device_checks.py.

Run with: python3 -m unittest discover -s scripts/tests
"""

import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

import device_checks  # noqa: E402  The script imports e2e_ime from its own directory.


class KeyboardReportTest(unittest.TestCase):
    def test_letter_without_modifiers_has_only_its_key_code(self):
        self.assertEqual(device_checks.keyboard_report("a"), [0x00, 0, 0x04, 0, 0, 0, 0, 0])

    def test_left_modifiers_are_combined_in_the_first_byte(self):
        self.assertEqual(device_checks.keyboard_report("ctrl+shift+k"), [0x03, 0, 0x0E, 0, 0, 0, 0, 0])

    def test_right_alt_is_its_own_modifier_bit(self):
        self.assertEqual(device_checks.keyboard_report("ralt+7"), [0x40, 0, 0x24, 0, 0, 0, 0, 0])

    def test_named_key_has_its_usage(self):
        self.assertEqual(device_checks.keyboard_report("enter"), [0x00, 0, 0x28, 0, 0, 0, 0, 0])

    def test_unknown_key_is_rejected(self):
        with self.assertRaises(KeyError):
            device_checks.keyboard_report("ctrl+f13")


if __name__ == "__main__":
    unittest.main()

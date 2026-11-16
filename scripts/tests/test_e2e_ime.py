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

"""Tests for scripts/e2e_ime.py and the layouts of tools/ime-probe/scripts/run_scenarios.py.

Run with: python3 -m unittest discover -s scripts/tests
"""

import pathlib
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

import e2e_ime  # noqa: E402

# Parts of `dumpsys input_method` on real devices with gesture navigation and three languages.
# API 37, Gboard 17.2.3: views give "pos=L,T-R,B", and the switcher button shows.
DUMP_POS = """\
  mNavigationBarController={mImeDrawsImeNavBar=true mNavigationBarFrame=android.inputmethodservice.navigationbar.NavigationBarFrame{d9f07d4 V.E...... ........ 0,2125-1080,2251 aid=64} mShowImeSwitcherButton=true mImeSwitcherButtonEnabled=true}
      <NavigationBarFrame pos=0,2125-1080,2251 visibility="0" scaleX,Y=1.00,1.00 padding=0,0,0,0>
        <NavigationBarView id="@id/#0x10203b6" pos=0,0-1080,126 visibility="0" scaleX,Y=1.00,1.00 padding=0,0,0,0>
          <NavigationBarInflaterView id="@id/#0x10203b5" pos=0,0-1080,126 visibility="0" scaleX,Y=1.00,1.00 padding=0,0,0,0>
            <FrameLayout id="@id/#0x1020384" pos=70,0-1010,126 visibility="0" scaleX,Y=1.00,1.00 padding=21,0,21,0>
              <FrameLayout id="@id/#0x1020380" pos=21,0-919,126 visibility="0" scaleX,Y=1.00,1.00 padding=0,0,0,0>
                <LinearLayout id="@id/#0x1020382" pos=0,0-898,126 visibility="0" scaleX,Y=1.00,1.00 padding=0,0,0,0>
                  <ReverseRelativeLayout pos=0,0-183,126 visibility="0" scaleX,Y=1.00,1.00 padding=0,0,0,0>
                    <KeyButtonView id="@id/#0x102037f" pos=0,0-183,126 visibility="0" scaleX,Y=1.00,1.00 padding=0,0,0,0/>
                  </ReverseRelativeLayout>
                  <ReverseRelativeLayout pos=715,0-898,126 visibility="0" scaleX,Y=1.00,1.00 padding=0,0,0,0>
                    <KeyButtonView id="@id/#0x1020385" pos=0,0-183,126 visibility="0" scaleX,Y=1.00,1.00 padding=0,0,0,0/>
                  </ReverseRelativeLayout>
                </LinearLayout>
                <LinearLayout id="@id/#0x1020381" pos=354,0-543,126 visibility="0" scaleX,Y=1.00,1.00 padding=0,0,0,0>
                </LinearLayout>
              </FrameLayout>
            </FrameLayout>
          </NavigationBarInflaterView>
        </NavigationBarView>
      </NavigationBarFrame>
"""
# API 33, Gboard 12.4: views give l, t, w, and h, and the switcher button is hidden (visibility 4).
DUMP_LTWH = """\
 mNavigationBarController={mImeDrawsImeNavBar=true mNavigationBarFrame=android.inputmethodservice.navigationbar.NavigationBarFrame{efaa575 V.E...... ........ 0,2138-1080,2264 aid=155} mShouldShowImeSwitcherWhenImeIsShown=false}
   <NavigationBarFrame l="0" t="2138" w="1080" h="126" visibility="0">
      <FrameLayout id="@id/#0x1020348" l="47" t="0" w="986" h="126" visibility="0">
       <FrameLayout id="@id/#0x1020344" l="21" t="0" w="944" h="126" visibility="0">
        <LinearLayout id="@id/#0x1020346" l="0" t="0" w="944" h="126" visibility="0">
         <ReverseRelativeLayout l="0" t="0" w="183" h="126" visibility="0">
          <KeyButtonView id="@id/#0x1020343" l="0" t="0" w="183" h="126" visibility="0"/>
         <ReverseRelativeLayout/>
         <ReverseRelativeLayout l="761" t="0" w="183" h="126" visibility="0">
          <KeyButtonView id="@id/#0x1020349" l="0" t="0" w="183" h="126" visibility="4"/>
         <ReverseRelativeLayout/>
        <LinearLayout/>
        <LinearLayout id="@id/#0x1020345" l="377" t="0" w="189" h="126" visibility="0">
        <LinearLayout/>
       <FrameLayout/>
      <FrameLayout/>
   <NavigationBarFrame/>
"""


def navigation_bar(dump):
    with mock.patch.object(e2e_ime, "adb", return_value=dump):
        return e2e_ime.ime_navigation_bar("SERIAL")


class ImeNavigationBarTest(unittest.TestCase):
    def test_switcher_is_found_from_positions_in_parents(self):
        # 70 + 21 + 0 + 715 + 0 + 183 / 2: the button at the right, as a screenshot shows it.
        self.assertEqual(navigation_bar(DUMP_POS), (126, 897))

    def test_hidden_switcher_is_none_in_the_older_format(self):
        self.assertEqual(navigation_bar(DUMP_LTWH), (126, None))

    def test_no_bar_when_the_ime_does_not_draw_one(self):
        self.assertIsNone(navigation_bar(DUMP_POS.replace("mImeDrawsImeNavBar=true", "mImeDrawsImeNavBar=false")))

    def test_bar_without_its_two_buttons_is_rejected(self):
        with self.assertRaises(RuntimeError):
            navigation_bar(DUMP_POS.replace("KeyButtonView", "View", 1))


class LayoutsForTest(unittest.TestCase):
    scenarios = e2e_ime.load_scenarios()

    def test_switcher_is_the_language_key_of_every_layout(self):
        layouts = self.scenarios.layouts_for(0, 1466, 1080, 2298, (897, 2361))
        self.assertEqual({lang: keys["GLOBE"] for lang, keys in layouts.items()},
                         {"en": (897, 2361), "ja": (897, 2361), "ko": (897, 2361)})

    def test_bottom_row_is_half_a_row_above_the_bottom_of_the_keys(self):
        # The region of the API 37 device without its navigation bar: rows of (2298 - 1466) / 4.733.
        enter = self.scenarios.layouts_for(0, 1466, 1080, 2298, None)["ja"]["ENTER"]
        self.assertEqual(enter, (994, 2210))

    def test_language_key_is_in_the_bottom_row_without_a_switcher(self):
        layouts = self.scenarios.layouts_for(0, 1466, 1080, 2298, None)
        self.assertEqual(layouts["ja"]["GLOBE"], layouts["ja"]["?"])


if __name__ == "__main__":
    unittest.main()

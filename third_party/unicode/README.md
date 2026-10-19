# Unicode Character Database

Files of the [Unicode Character Database](https://www.unicode.org/ucd/), version 16.0.0, copied unchanged. They are licensed under the Unicode License v3 in [LICENSE](LICENSE). The source and version are in [METADATA](METADATA).

| File | Used for |
|---|---|
| `ucd/EastAsianWidth.txt` | Wide (W, F) and ambiguous (A) characters |
| `ucd/extracted/DerivedGeneralCategory.txt` | Zero-width characters (Mn, Me, Cf) |
| `ucd/DerivedCoreProperties.txt` | Default-ignorable code points (`Default_Ignorable_Code_Point`), which `TerminalGate` refuses in URLs ([decisions/0006](../../docs/decisions/0006-gate-defaults.md)) |
| `ucd/extracted/DerivedCombiningClass.txt`, `ucd/extracted/DerivedDecompositionType.txt`, `ucd/DerivedAge.txt` | The diacritics that give the row and column of a Unicode placeholder of the kitty graphics protocol, by kitty's rule ([decisions/0038](../../docs/decisions/0038-kitty-graphics.md)) |

`scripts/generate_unicode_tables.py` generates the character width tables of `vtfx-core` (`UnicodeWidthTables.kt`), the diacritics of placeholders (`PlaceholderDiacritics.kt` in `vtfx-core`), and the default-ignorable code points of `vtfx-gate` (`UnicodeDefaultIgnorables.kt`), all committed, from these files ([decisions/0011](../../docs/decisions/0011-unicode.md), [decisions/0029](../../docs/decisions/0029-import-without-gradle.md)). It reads only these copies and does not download anything.

To move to another Unicode version, replace the files with those of the new version from `https://www.unicode.org/Public/<version>/ucd/`, update `version`, the archive URL, and `last_upgrade_date` in `METADATA`, and the version in this file, run `scripts/generate_unicode_tables.py`, and then run `scripts/check.sh`. The version of the tables (`CharWidth.UNICODE_VERSION`) is read from the first line of the files.

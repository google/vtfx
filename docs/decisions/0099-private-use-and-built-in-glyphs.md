# Private use characters and glyphs that vtfx draws itself

- Status: Provisional
- Date: 2026-11-02
- Related: Requirement 5.2 of [REQUIREMENTS.md](../REQUIREMENTS.md), sections 7.1 and 7.3 of [emulation.md](../design/emulation.md), [fonts.md](../design/fonts.md), [0079](0079-emulation-scope.md), [0086](0086-fonts.md), [0090](0090-distribution.md)

## Background

Shell prompts, tmux status lines, and editors draw icons and separators from private use characters (U+E000–U+F8FF, U+F0000–U+FFFFD, U+100000–U+10FFFD), which fonts such as Nerd Fonts and Powerline fonts fill. Tools also draw progress bars, graphs, and spinners from braille and from the symbols for legacy computing. UAX #11 classifies private use characters as East Asian Ambiguous, and Android's system fonts have glyphs for none of these characters.

Ten terminals were compared: kitty, Ghostty, WezTerm, Alacritty, foot, Contour, xterm.js, Windows Terminal, VTE, and iTerm2. Five have a setting that makes ambiguous characters two cells wide. Of those, only WezTerm keeps private use characters one cell wide; Windows Terminal, VTE, and iTerm2 widen them with the rest. The hosts' width functions count private use characters as one cell. glibc's `wcwidth()` does so in every locale, `ja_JP.UTF-8` included, and so do musl, utf8proc, zsh, and tmux. Only vim and Neovim with `ambiwidth=double`, unless `setcellwidths()` overrides them, and fish with `$fish_ambiguous_width` set to 2 count them as two cells.

## 1. Private use characters are one cell wide with either ambiguous width

Private use characters (general category Co) are width 1 whether `AmbiguousWidth` is `Narrow` or `Wide`. `scripts/generate_unicode_tables.py` leaves them out of the ambiguous table (section 7.1 of [emulation.md](../design/emulation.md)).

**Why.** The width has to match what the host counts, or the cursor goes out of place (topic 6 of [0079](0079-emulation-scope.md)). The shells and tmux that draw prompts and status lines count private use characters as one cell whatever the locale. So with `Wide`, two-cell icons pushed every prompt that has a Powerline separator or a Nerd Font icon out of line. The ambiguous characters that `Wide` is for are the symbols, Greek, and Cyrillic that CJK fonts draw full width, and private use characters are not among them. The icons themselves are drawn one cell wide by Nerd Font Mono, Powerline fonts, and the prompts that use them. U+10EEEE, the placeholder of the kitty graphics protocol, was the one private use character kept one cell wide before. It is now covered by the rule, so it needs no exception.

**Alternatives considered.**
- **Keep private use characters ambiguous**, as Windows Terminal, VTE, and iTerm2 do: matches vim with `ambiwidth=double` and no `setcellwidths()`. It does not match the shells, which draw most of the icons.
- **A third `AmbiguousWidth`, wide except private use** ([0087](0087-public-api.md) lets the enum take new entries): an app would have to explain a choice between two kinds of wide, while the one that matches the shells is already clear. It can still be added if users of vim's `ambiwidth=double` need it.

**Impact of reversing.** Remove the private use exception from `classify()` in `scripts/generate_unicode_tables.py` and regenerate the tables. Restore the exception for U+10EEEE in `CharWidth.of`. Update section 7.1 of emulation.md, topic 6 of 0079, and the KDoc of `AmbiguousWidth`.

## 2. Which characters vtfx draws itself, and how

vtfx draws as shapes the characters whose glyphs are meant to reach the edges of the cell and join the next cells: box drawing and block elements, braille, the separators of Powerline, the symbols for legacy computing, and the progress bar and git branch symbols of the private use area. It leaves pictures, such as the other icons of Nerd Fonts, to fonts. The drawn characters take precedence over every font, the user's included, and there is no setting to turn them off. vtfx writes the shapes itself, from the code charts and the descriptions of the characters, and ports no other terminal's code. The ranges drawn are listed in section 7.3 of [emulation.md](../design/emulation.md).

**Why.** Android's system fonts have no glyphs for braille, the private use area, or most of the symbols for legacy computing, so these showed as boxes for missing glyphs. The terminals compared in the background all draw some of them, and box drawing and Powerline separators are the common core. A font's glyphs match the cell only when its metrics match the cell, which fallback fonts do not; drawn shapes always fill the cell, so that segments of prompts, bars, and graphs join without gaps. All the compared terminals that draw them let the drawn shapes take precedence by default. Taking precedence always means that the screen looks the same with and without a Nerd Font. It also needs no knowledge of which font has a glyph, which Android's text drawing does not give and which vtfx would otherwise have to read from the fonts that users import. Most of the shapes follow from a rule, such as dots, sextants, eighths, triangles, and lines between the middles of edges, so writing them takes little code. Porting them would bring code under other licenses into vtfx, which keeps third-party material only in `third_party/unicode` (topic 3 of [0090](0090-distribution.md)).

**Alternatives considered.**
- **Let a font that has the glyph win**, or offer a setting for it, as some of the compared terminals do: vtfx would have to know which fonts cover which characters, and the screen would differ with the font. A setting can be added to `TerminalStyle` if users ask for one.
- **Port the custom glyphs of xterm.js** (MIT): less work, but MIT notices in the sources and the artifacts, a change to topic 3 of 0090, and a decision on licensing that only humans can make.
- **Bundle a symbol font**, such as Symbols Nerd Font: its glyphs come under several licenses (CC BY, OFL, Apache, and some without a license), and it still would not join across cells.

**Impact of reversing.** Remove the families from `BuiltinGlyphs` in vtfx-compose, with their tests and screenshot goldens, and update section 7.3 of emulation.md.

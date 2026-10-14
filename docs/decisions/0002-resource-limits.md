# Resource limits

- Status: Approved (2026-10-13, approved by a human in the final review. On 2026-10-14, a human added the memory budget of the scrollback after a security review)
- Date: 2026-10-12
- Related: Requirement 6.1 (handling resource exhaustion) and section 8 (the acceptable range and limits for resource exhaustion), `TerminalLimits`, `Params`, `Line`

## Background

Host output is not trustworthy. It must not be able to make the terminal use memory or processing time without bound through huge repetitions, very long strings, bloated scrollback, and so on. Requirement 6.1 asks for the acceptable range and the limits to be decided.

## Decision

### Policy

- Memory used because of host output is bounded by the configured limits. Input beyond a limit is **silently truncated**. It is neither an exception nor a crash.
- Processing time is kept linear (with a small constant factor) in the number of input bytes. A few bytes of input must not cause work beyond the size of the screen.
- For notifications to the main thread and the app caused by host output (redraws, bells, titles), at most one of each kind waits for the main thread. This keeps notifications from piling up in proportion to host output while the main thread is behind (added 2026-10-13 after a code review).
- Values given by the app (the screen size, `TerminalLimits`) being out of range is a programming error, and `IllegalArgumentException` is thrown.

### Limits

| Target | Limit | Setting | When exceeded |
|---|---|---|---|
| Columns, rows | 2048 | Not configurable (`TerminalLimits.MAX_DIMENSION`) | `resize` and the like throw (an error of the app) |
| Scrollback lines | 10,000 by default (at most 1,000,000) | `TerminalLimits.scrollbackLines` | The oldest lines are dropped |
| Scrollback memory | 96 bytes per line and 13 bytes per cell, for `scrollbackLines` lines of the current width, by the estimate of `CompactLine` (15.8 MiB for 10,000 lines of 120 columns) | Follows `scrollbackLines` and the width | The oldest lines are dropped, even before there are `scrollbackLines` of them. The newest line is kept even if it alone is over the budget |
| Length of OSC and DCS strings | 256 Ki UTF-16 units by default (at most 16 Mi) | `TerminalLimits.maxStringLength` | The whole string is dropped (not truncated and executed) |
| Number of parameters | 32 | Not configurable | The 33rd and later are dropped |
| Parameter values | 65535 | Not configurable | Set to 65535 |
| Combining characters per cell | 32 UTF-16 units | Not configurable | Later combining characters are dropped |
| REP (`CSI Ps b`) count | The number of cells on the screen (columns × rows) | Not configurable | The count is truncated |
| Length of OSC 8 URIs | 2083 characters (the recommendation of the OSC 8 specification) | Not configurable | Not made a link |
| Length of OSC 8 ids | 250 UTF-16 units (as VTE limits it) | Not configurable (`Emulator.MAX_LINK_ID_LENGTH`) | Not made a link |
| Saved titles (XTWINOPS 22) | 10. The total length up to the limit of OSC and DCS strings | The count is not configurable (`Emulator.MAX_SAVED_TITLES`). The length is `TerminalLimits.maxStringLength` | The oldest are dropped |
| Number of hyperlinks held at once | 4096 | Not configurable | Links not referenced by any cell are dropped, and if that is still not enough, the new link is not attached. The search for referenced links (looking at every cell of the screen and scrollback) happens at most once per 2048 requests for new links, and if the table is full in between, new links are not attached |

Why the hyperlink search is thinned out: when the table is full, searching on every request for a new link would let a few bytes of OSC 8 make the terminal look at the whole scrollback each time (found in M12; `linksInUseAreNotSearchedForEveryNewLink` in `EmulationCoverageTest`). With thinning, the work of the search is linear in the number of bytes of OSC 8 input. In exchange, after references disappear from a full table, new links are not attached until the next search.

Why the total length of saved titles is limited: one title can be as long as the string limit, which is up to about 512 KiB for one with the default limit (256 Ki UTF-16 units). If only the count were limited, the host could set and save different long titles and make the terminal hold 10 times that. Limiting the total keeps the memory of saved titles from exceeding that of one current title. The length is counted in UTF-16 units, as in the parser (added 2026-10-13).

Why the length of strings is counted in UTF-16 units: it directly bounds the memory of the held strings. Counting in code points, a string consisting only of characters outside the BMP (emoji and so on) would take twice the memory with the same limit. It was originally recorded as code points, but the implementation counted UTF-16 units from the start, so the record was aligned with the implementation. However, the implementation had a bug in which adding a two-unit character one unit before the limit exceeded the limit by one unit, which was fixed (2026-10-13; `countsTheStringLimitInUtf16Units` and `discardsAStringThatACharacterOfTwoUnitsTakesPastTheLimit` in `ParserTest`).

Why the id of OSC 8 is limited too: the hyperlink table keeps the id with each link, and its only bound was the length of OSC strings, so 4096 links with ids of 256 Ki units made the table hold 1–2 GiB, from about 1 GiB of output and with no character printed (found in a security review, 2026-10-14). Limiting the URI alone did not bound the table. With both limits, the table takes at most about 4096 × 2333 units, under 20 MiB. A link with a longer id is not made, as for a longer URI, rather than made without its id, which would join cells that the host meant to keep apart (added 2026-10-15).

Why strings are not truncated and executed: cutting an OSC 52 clipboard value or an OSC 8 URL in the middle turns it into a value with a different meaning.

Since lines were stored compactly in M11, the memory of the default 10,000 lines of scrollback is about 3 MiB for typical 120-column output, and about 14 MiB where every cell has a different color ([0014](0014-performance.md)).

Why the scrollback has a memory budget besides the number of lines: a cell can hold 32 UTF-16 units of combining characters, so lines full of them took about 105 MiB for 10,000 lines of 120 columns, 7 times the lines with a color in every cell, and more on a wide display (found in a security review, 2026-10-14). The number of lines and the columns alone did not bound the memory to the target of [0014](0014-performance.md). The budget scales with both, so that the app's settings keep their meaning, and is set so that lines with a color in every cell (about 1490 bytes at 120 columns) and text with a two-unit cluster in every cell, as in Indic scripts (about 1540 bytes, since a line keeps its clusters in one string), keep all their lines (the budget is 1656 bytes a line at 120 columns); output with long clusters, or with a hyperlink and a color in every cell, keeps fewer. Alternatives considered: lowering the 32 units per cell would break long emoji sequences and still exceed the target; dropping the combining characters of old lines over the budget would keep the count of lines but lose their content; revising the target would leave wide displays with hundreds of MiB.

## Alternatives considered

- **Throw an exception when a limit is exceeded**: If host output caused exceptions, the app would have to handle them, and handling them wrongly would stop the session (denial of service). Truncating is safer.
- **Do not limit the REP count**: The 9 bytes of `CSI 65535 b` would write 65535 characters, 7000 times the work relative to the input.

## Impact of reversing

Change the defaults and constants of `TerminalLimits`. To change the behavior at the limits, update the corresponding tests in `TerminalTest` and `ParserTest`.

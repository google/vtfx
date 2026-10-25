# Resource limits

- Status: Approved (2026-10-13, approved by a human in the final review. On 2026-10-14, a human added the memory budget of the scrollback after a security review); the premise that one saved title can be as long as the string limit superseded by [0063](0063-window-title-limits.md), which limits a title to 1024 UTF-16 units
- Date: 2026-10-12
- Related: Requirement 6.1 (handling resource exhaustion) and section 8 (the acceptable range and limits for resource exhaustion), section 10 of [emulation.md](../design/emulation.md), `TerminalLimits`, `Params`, `Line`

## Background

Host output is not trustworthy. It must not be able to make the terminal use memory or processing time without bound through huge repetitions, very long strings, bloated scrollback, and so on. Requirement 6.1 asks for the acceptable range and the limits to be decided.

## Decision

- Memory used because of host output is bounded by the configured limits, and input beyond a limit is **silently truncated** (or dropped), neither an exception nor a crash.
- Processing time is kept linear, with a small constant factor, in the number of input bytes.
- At most one notification of each kind caused by host output (redraws and the other notifications that a display passes to the main thread; bells and titles reach the app once per `feed`, on its thread) waits for the main thread, so that notifications do not pile up in proportion to host output while the main thread is behind.
- Values given by the app (the screen size, `TerminalLimits`) out of range are programming errors, and throw `IllegalArgumentException`.
- The scrollback has a memory budget besides its number of lines, which scales with the number of lines and the width.

The limits and their values are in section 10 of [emulation.md](../design/emulation.md); the limits of images were added by [0080](0080-kitty-graphics.md). The reasons for the less obvious ones:

- **Strings are dropped whole, not truncated and executed**: cutting an OSC 52 clipboard value or an OSC 8 URL in the middle turns it into a value with a different meaning.
- **String lengths are counted in UTF-16 units**: this bounds the memory of the held strings directly. Counting in code points, a string of characters outside the BMP (emoji and so on) would take twice the memory with the same limit.
- **The search for hyperlinks in use is thinned out**: when the table is full, searching on every request for a new link would let a few bytes of OSC 8 make the terminal look at the whole scrollback each time (`linksInUseAreNotSearchedForEveryNewLink` in `EmulationCoverageTest`). With thinning, the work of the search is linear in the bytes of OSC 8 input. In exchange, after references disappear from a full table, new links are not attached until the next search.
- **The id of OSC 8 is limited, not only the URI**: the hyperlink table keeps the id with each link, so with only the string limit 4096 links with long ids could make the table hold 1–2 GiB from about 1 GiB of output, with no character printed (found in a security review). With both limits, the table takes under 20 MiB. A link with a longer id is not made, rather than made without its id, which would join cells that the host meant to keep apart.
- **The total length of saved titles is limited, not only their count**: one title can be as long as the string limit (about 512 KiB with the default), so with only a count the host could make the terminal hold 10 long titles. Limiting the total keeps the memory of saved titles within that of one current title.
- **The scrollback has a memory budget**: a cell can hold 32 UTF-16 units, so lines full of combining characters took about 7 times the memory of lines with a color in every cell, and more on a wide display (found in a security review); the number of lines and the columns alone did not bound the memory to the target of [0014](0014-performance.md). The budget is set so that lines with a color in every cell, and text with a two-unit cluster in every cell as in Indic scripts, keep all their lines; output with long clusters, or with a hyperlink and a color in every cell, keeps fewer.

## Alternatives considered

- **Throw an exception when a limit is exceeded**: If host output caused exceptions, the app would have to handle them, and handling them wrongly would stop the session (denial of service). Truncating is safer.
- **Do not limit the REP count**: The 9 bytes of `CSI 65535 b` would write 65535 characters, 7000 times the work relative to the input.
- **For the scrollback, lower the 32 units per cell**: would break long emoji sequences and still exceed the target. **Drop the combining characters of old lines over the budget**: would keep the count of lines but lose their content. **Revise the target of 0014**: would leave wide displays with hundreds of MiB.

## Impact of reversing

Change the defaults and constants of `TerminalLimits` and the constants named in section 10 of [emulation.md](../design/emulation.md), and update that section. To change the behavior at the limits, update the corresponding tests in `TerminalTest`, `ParserTest`, and `ResourceExhaustionTest`.

# Bounding the search's work per redraw

- Status: Provisional
- Date: 2026-10-22
- Related: Section 13 of [compose-input.md](../design/compose-input.md), [0047](0047-search.md), [0014](0014-performance.md), `readSearchLines` in `vtfx-compose`, `SearchIndex` and `Terminal.searchLines` in `vtfx-core`

## Background

[0047](0047-search.md) keeps a search up to date at each redraw after output, on the main thread. The display reads the lines a thousand at a time, each read taking the session's lock apart so that the output thread is not held up for long, until a read reaches the screen.

Between two reads, the output thread may write. When the host writes faster than the reads go (`yes`, `cat` of a large log), each write scrolls more lines than a read reads: the next read starts at the oldest line that the terminal still keeps, which has moved on, and never reaches the screen. The loop had no other end, so the main thread stopped, and with it drawing and the user's Ctrl-C.

## Decision

1. **A redraw reads a bounded number of times.** It reads at most twice as many times as reading the lines that the terminal kept at its first read takes, plus a few: enough to read everything once without output in between, and to start over once when the lines are numbered again. When output scrolls faster than that, the redraw keeps what it read and leaves the rest to the next redraw, which that output brings. While the output goes on, the matches of the newest lines may be missing; when it stops, the last redraw reads up to the screen.

## Alternatives considered

- **Read up to the screen as it was at the first read.** Bounded while the lines keep their numbers, but output that clears the scrollback (ED 3) between the reads numbers them again and starts the search over at every read, so it needs a bound on the reads anyway.
- **Hold the session's lock for the whole update.** It always ends, but holds up the output thread for as long as reading the whole scrollback takes, which the reads a thousand lines at a time avoid.
- **Search on another thread.** See [0047](0047-search.md).

## Impact of reversing

Change `readSearchLines` in `vtfx-compose` and section 13 of [compose-input.md](../design/compose-input.md).

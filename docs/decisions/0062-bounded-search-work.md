# Bounding the search's work per redraw

- Status: Provisional
- Date: 2026-10-22
- Related: Section 13 of [compose-input.md](../design/compose-input.md), [0047](0047-search.md), [0014](0014-performance.md), `readSearchLines` in `vtfx-compose`, `SearchIndex` and `Terminal.searchLines` in `vtfx-core`

## Background

[0047](0047-search.md) keeps a search up to date at each redraw after output, on the main thread. The display reads the lines a thousand at a time, each read taking the session's lock apart so that the output thread is not held up for long, until a read reaches the screen.

Between two reads, the output thread may write. When the host writes faster than the reads go (`yes`, `cat` of a large log), each write scrolls more lines than a read reads: the next read starts at the oldest line that the terminal still keeps, which has moved on, and never reaches the screen. The loop had no other end, so the main thread stopped, and with it drawing and the user's Ctrl-C.

## Decision

1. **A redraw reads a bounded number of times.** It reads at most twice as many times as reading the lines that the terminal kept at its first read takes, plus a few: enough to read everything once without output in between, and to start over once when the lines are numbered again. When output scrolls faster than that, the redraw keeps what it read and leaves the rest to the next redraw, which that output brings. While the output goes on, the matches of the newest lines may be missing; when it stops, the last redraw reads up to the screen.

2. **A line that autowrap joins through more than 100 rows is searched in parts of 100 rows** (`MAX_SEARCH_TEXT_ROWS`), counted from where the search started reading it: its first row, or the oldest line kept when the search started after that row left. A match does not cross from one part to the next. This replaces, for such lines, "rows that autowrap joins are one text" of item 5 of [0047](0047-search.md).

   [0047](0047-search.md) item 2 lets an update search only the lines that entered the scrollback since and the screen, because a text that ended in the scrollback does not change. A text that goes on into the screen has not ended, so each update read it again from its first row. Host output without line breaks (`base64 -w0`, minified JSON) makes one text of the whole scrollback and the screen, so each update read and searched the whole scrollback with the session's lock held, and one read was not bounded by the thousand lines. A part that ends in the scrollback does not change either, so it is searched once; an update reads the lines that entered the scrollback since, fewer than 100 rows of the last part, and the screen. 100 rows is a few screens: small next to the scrollback, while a match is lost only where it crosses the end of a part of a line at least that long.

## Alternatives considered

- **Read up to the screen as it was at the first read.** Bounded while the lines keep their numbers, but output that clears the scrollback (ED 3) between the reads numbers them again and starts the search over at every read, so it needs a bound on the reads anyway.
- **Hold the session's lock for the whole update.** It always ends, but holds up the output thread for as long as reading the whole scrollback takes, which the reads a thousand lines at a time avoid.
- **Search on another thread.** See [0047](0047-search.md).
- **Keep searching a long line where the last update left it**, keeping the state of the matcher and the text of the line's rows in the scrollback, so that no match is lost. The text, the columns of its characters, and a match that goes on past the scrollback would all have to be continued across reads; much more state for matches at the ends of parts of very long lines.
- **Parts as long as a read (1,000 rows).** Fewer lost matches, but up to ten times the work at each redraw while such a line goes on, and the parts would depend on how many lines a caller reads at a time.

## Impact of reversing

Change `readSearchLines` in `vtfx-compose`, `MAX_SEARCH_TEXT_ROWS`, `SearchIndex`, and `Terminal.searchLines` in `vtfx-core`, and section 13 of [compose-input.md](../design/compose-input.md).

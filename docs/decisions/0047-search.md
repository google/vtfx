# Searching the display

- Status: Approved (2026-10-20, approved by a human, with the required search colors of `TerminalColors` in item 8); items 2 and 5 superseded in part by [0081](0081-resource-limits.md); in item 5, copying gives concealed cells as blanks since [0049](0049-concealed-text-not-copied.md)
- Date: 2026-10-20
- Related: Requirements 4.1, 6.1, and 6.5, section 13 of [compose-input.md](../design/compose-input.md), [0012](0012-touch-and-selection.md), [0014](0014-performance.md), [0019](0019-extensibility.md), [0031](0031-desktop-pointer-and-terminal-state.md), [0036](0036-adding-parameters.md), [0037](0037-api-shape-before-1.0.md), `SearchIndex` in `vtfx-core`, `TerminalState`

## Background

[0019](0019-extensibility.md) made matching, highlighting, and scrolling to a match built in, with the search field the app's, and left where the state of a search lives and how matches are named to a separate decision. A search that follows the text as the user types (an incremental search) must keep up with two kinds of change:

- Each change of the query searches again. The default scrollback holds 10,000 lines of up to 120 columns.
- Output goes on while the user searches, as in a log that keeps flowing. Lines move into the scrollback and leave it, and resizing reflows them. The selection avoids this by being dropped whenever the display changes ([0012](0012-touch-and-selection.md)); a search dropped on every output could not be used on a flowing log.

## Decision

The behavior is described in section 13 of [compose-input.md](../design/compose-input.md).

1. **The state of a search belongs to the display**, as the selection does ([0031](0031-desktop-pointer-and-terminal-state.md)): `TerminalDisplay` holds it, and `TerminalState` is the app's handle on it, with `search`, `findNext`, `findPrevious`, `clearSearch`, and `searchResult`. Two `Terminal`s showing one session search apart, and a session without a UI has no search. Moving to the next match is an event, like Select all, so the API is calls on the state rather than a parameter of `Terminal`.
2. **Matches are named by line numbers that survive scrolling.** A line keeps the number that the count of lines pushed into the scrollback gave it, which image placements already use; the first row of the screen is that count. Lines in the scrollback do not change, so a search keeps their matches and, on each output, searches only the lines that entered the scrollback since and the screen. A match whose line left the scrollback goes with it. Resizing (which reflows), clearing the scrollback (ED 3), and switching between the main and the alternate screen number the lines again, and the search starts over.
3. **The text of the scrollback is kept while searching**, so that each change of the query searches it without reading and laying out the lines again, which is most of the work: the text without the blanks at the end of each line, two bytes for the column of each character, and the concealed characters. It goes when the search is cleared or the display leaves the composition. The time on ART has not been measured (section 12 of [compose-input.md](../design/compose-input.md)).
4. **Literal text, with an option to ignore case**, compared one UTF-16 unit at a time as `String.regionMatches` does. The search takes time linear in the text and the query (Knuth-Morris-Pratt), so text that the host decides cannot make it slow. Regular expressions are not offered: Android's cannot be stopped once started ([0019](0019-extensibility.md)), and a search runs on the main thread at every keystroke, also on half-typed patterns. Normalizing width or Unicode forms is not done until asked for.
5. **What is text is what the links read and what copying gives.** Rows that autowrap joins are one text, a match does not cross a line break, the blank that autowrap leaves before a wide character is not text, and the blanks at the end of a line are not text. A match that covers a concealed cell (SGR 8) is not a match, as for links: the display does not draw the cell, so a highlight there would show text that the user cannot see.
6. **At most 1,000 matches are kept**, the newest, so that text full of matches, which the host decides, takes bounded memory and drawing; `TerminalSearchResult.isMatchCountCapped` tells the app to show the count as a lower bound.
7. **The search starts from the bottom and goes up**, as terminals do: a new query makes current the nearest match at or above the current one (or the bottom of the display), so that typing more of a word keeps the view where it is. Only moving to a match (a new query, `findNext`, `findPrevious`) scrolls the display; output and the user's input do not move it for the search.
8. **Colors**: `TerminalColors` takes `searchMatch` and `searchCurrentMatch`, required like `selection`. Before 1.0, parameters may be added without keeping the previous signature ([0036](0036-adding-parameters.md)).

## Alternatives considered

- **Hold the search in `TerminalSession`**: one search for every display, and one for JVM programs without a UI, but the session holds no display state ([0026](0026-session-module.md)), and a search scrolls a display. A JVM program can read the lines with `TerminalSession.snapshot`.
- **Drop the search on output, as the selection**: no line numbers needed, but a flowing log could not be searched.
- **Read and lay out every line at each change of the query**: no memory kept, but several times the work, all of it on the main thread, with the session's lock held while the lines are copied.
- **Search on another thread**: the main thread would stay free, but stale results would have to be dropped as the query and the output change, and the modules that do not depend on Android have no executor to run it on. Not needed at the measured cost.
- **Regular expressions**, and **a query as a parameter of `Terminal`**: see items 4 and 1.

## Impact of reversing

Change `SearchIndex` and `Terminal.searchLines` in `vtfx-core`, the search of `TerminalDisplay`, `TerminalState`, and the drawing in `vtfx-compose`, and section 13 of [compose-input.md](../design/compose-input.md). Removing the colors from `TerminalColors` or the functions from `TerminalState` changes the public API.

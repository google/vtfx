# The length and the characters of the window title

- Status: Approved (2026-10-23, approved by a human in a review of the review queue)
- Date: 2026-10-22
- Related: Requirement 6.5, sections 5 and 10.2 of [emulation.md](../design/emulation.md), [0002](0002-resource-limits.md), [0009](0009-emulation-scope.md), `Emulator.setTitle`, `TerminalSessionListener.onTitleChanged`, `TerminalSession.title`

## Background

The host sets the window title with OSC 0 and 2. The title was passed to the app as the host sent it, up to the limit on OSC strings (`TerminalLimits.maxStringLength`, 256 Ki UTF-16 units by default), with every character that the parser keeps in strings: line and paragraph separators, and the bidirectional controls that reorder text (U+202E and the like). The parser already drops C0 and C1 controls in OSC strings, but nothing in the title's contract said so. The KDoc said only that the title is untrusted.

Apps naturally show the title in places outside the terminal: the Activity's title, the task description of the recents screen, a notification. Those go through Binder, whose transactions are limited to about 1 MB for the process, so a title of half a megabyte in several fields can throw `TransactionTooLargeException`. Bidirectional controls and line breaks in a title let the host make the title read differently from its characters, for example to spoof a file name or the host name that a user checks in the title. Each app would have to know to clean the title up, against requirement 6.5 (safe defaults).

## Decision

1. **A title is at most 1024 UTF-16 units** (`Emulator.MAX_TITLE_LENGTH`). A longer title is cut there, without splitting a surrogate pair. Cutting rather than dropping keeps the start of a long title, such as a long working directory, which is the part a user reads. 1024 is far above what shells and editors set (a user, a host, and a path), and small for Binder.
2. **Control and reordering characters are removed** from the title: C0 controls, DEL, C1 controls (U+0080–U+009F), which the title therefore excludes whatever the parser does, U+2028 and U+2029, and the bidirectional formatting characters (U+061C, U+200E, U+200F, U+202A–U+202E, U+2066–U+2069). Other format characters stay: ZWJ and variation selectors are part of emoji, and the zero-width characters do not reorder or break the text. The characters are removed rather than replaced, since a replacement character shows nothing more to the user.
3. The limits apply when the title is set by OSC 0 and 2, so `TerminalSession.title`, `onTitleChanged`, the saved titles (XTWINOPS 22, 23), and the title that esctest reads all see the same string. The length is cut after the characters are removed.

## Alternatives considered

- **Document the length and the characters, and leave the cleaning to the app**: The smallest change, but every app that shows the title would need the same code, and the default would still be unsafe.
- **Drop a title over the limit**, as for OSC strings over the string limit: A long title would leave the previous one in place, which is more confusing than a cut one. The string limit drops the whole string because a cut control string could do something else; a cut title is still a title.
- **A limit in `TerminalLimits`**: No app has needed another value, and a parameter can be added later ([0036](0036-adding-parameters.md)).
- **Remove all format characters (Cf)**: Would break emoji sequences with ZWJ in titles, which some prompts use.

## Impact of reversing

Remove the cleaning in `Emulator.setTitle` (or its caller for OSC 0 and 2), the row of the table in section 10.2 of emulation.md, the line in section 5, and the tests in `EmulationCoverageTest`. Apps that rely on the cleaned title would need their own.

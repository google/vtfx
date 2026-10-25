# Resource limits

- Status: Approved
- Date: 2026-10-25
- Related: Requirement 6.1 (resource exhaustion, unauthorized reading of the clipboard), Requirement 6.5 (safe defaults), section 8 of [REQUIREMENTS.md](../REQUIREMENTS.md) (the acceptable range and limits for resource exhaustion), sections 5 and 10 of [emulation.md](../design/emulation.md), section 4.1 of [gate-policy.md](../design/gate-policy.md), section 13 of [compose-input.md](../design/compose-input.md), [0006](0006-gate-defaults.md), [0079](0079-emulation-scope.md), [0014](0014-performance.md), [0036](0036-adding-parameters.md), [0037](0037-api-shape-before-1.0.md), [0080](0080-kitty-graphics.md), [0044](0044-boundaries-in-gradle-and-classes.md), [0047](0047-search.md)

## Background

Host output is not trustworthy. It must not be able to make the terminal, or the app around it, use memory or processing time without bound: through huge repetitions, very long strings, bloated scrollback, or replies far larger than the output. Requirement 6.1 asks for the acceptable range and the limits to be decided.

## 1. Policy

1. Memory used because of host output is bounded by the configured limits, and input beyond a limit is silently truncated or dropped, neither an exception nor a crash.
2. Processing time is kept linear, with a small constant factor, in the number of input bytes.
3. At most one notification of each kind caused by host output waits for the main thread; bells and titles reach the app at most once per `feed`, on its thread.
4. Values given by the app (the screen size, `TerminalLimits`) out of range are programming errors, and throw `IllegalArgumentException`.

Section 10.1 of [emulation.md](../design/emulation.md) describes the policy, and section 10.2 the limits and their values; the limits of images are explained in [0080](0080-kitty-graphics.md).

**Why.** Item 3 keeps notifications from piling up in proportion to host output while the main thread is behind. The reasons for the less obvious limits are in topics 2 to 7.

**Alternatives considered.**
- **Throw an exception when a limit is exceeded**: if host output caused exceptions, the app would have to handle them, and handling them wrongly would stop the session (denial of service). Truncating is safer.
- **Do not limit the REP count**: the 9 bytes of `CSI 65535 b` would write 65535 characters, 7000 times the work relative to the input.

**Impact of reversing.** Change the defaults and constants of `TerminalLimits` and the constants named in section 10 of [emulation.md](../design/emulation.md), and update that section. To change the behavior at the limits, update the corresponding tests in `TerminalTest`, `ParserTest`, and `ResourceExhaustionTest`. This applies to the limits of topics 2 to 4 as well.

## 2. Strings

OSC, DCS, and APC strings beyond the string limit are dropped whole, and string lengths are counted in UTF-16 units. The saved titles (XTWINOPS 22) are limited in their total length as well as in their count. Section 10.2 of [emulation.md](../design/emulation.md) gives the limits, and section 5 the deviations from the references.

**Why.**
- Dropping a string whole keeps it from being executed with a meaning that the host did not send.
- Counting in UTF-16 units bounds the memory of the held strings directly.

**Alternatives considered.**
- **Truncate a string over the limit and execute it**: cutting an OSC 52 clipboard value or an OSC 8 URL in the middle turns it into a value with a different meaning. A title is cut instead (topic 5), since a cut title is still a title.
- **Count lengths in code points**: a string of characters outside the BMP (emoji and so on) would take twice the memory with the same limit.

**Impact of reversing.** As in topic 1.

## 3. Hyperlinks

The id of OSC 8 is limited as well as the URI, and a link with a longer id is not made. When the table of hyperlinks is full, the search for the links that cells still reference is thinned out. Section 10.2 of [emulation.md](../design/emulation.md) gives the limits and how often the search happens.

**Why.**
- The table keeps the id with each link, so with only the string limit, 4096 links with long ids could make the table hold 1–2 GiB from about 1 GiB of output, with no character printed. With both limits, the table takes under 20 MiB.
- With thinning, the work of the search is linear in the bytes of OSC 8 input. In exchange, after references disappear from a full table, new links are not attached until the next search.

**Alternatives considered.**
- **Make a link with a longer id without its id**: would join cells that the host meant to keep apart.
- **Search on every request for a new link while the table is full**: would let a few bytes of OSC 8 make the terminal look at the whole scrollback each time (`linksInUseAreNotSearchedForEveryNewLink` in `EmulationCoverageTest`).

**Impact of reversing.** As in topic 1.

## 4. Memory budget of the scrollback

The scrollback has a memory budget besides its number of lines, which scales with the number of lines and the width (section 10.2 of [emulation.md](../design/emulation.md)).

**Why.** A cell can hold 32 UTF-16 units, so lines full of combining characters take about 7 times the memory of lines with a color in every cell, and more on a wide display: the number of lines and the columns alone do not bound the memory to the target of [0014](0014-performance.md). The budget is set so that lines with a color in every cell, and text with a two-unit cluster in every cell as in Indic scripts, keep all their lines; output with long clusters, or with a hyperlink and a color in every cell, keeps fewer.

**Alternatives considered.**
- **Lower the 32 units per cell**: would break long emoji sequences and still exceed the target.
- **Drop the combining characters of old lines over the budget**: would keep the count of lines but lose their content.
- **Revise the target of [0014](0014-performance.md)**: would leave wide displays with hundreds of MiB.

**Impact of reversing.** As in topic 1.

## 5. The window title

1. A title set by OSC 0 and 2 is cut to 1024 UTF-16 units (`Emulator.MAX_TITLE_LENGTH`), without splitting a surrogate pair.
2. Control and reordering characters are removed from it: C0 controls, DEL, C1 controls (U+0080–U+009F), U+2028 and U+2029, and the bidirectional formatting characters (U+061C, U+200E, U+200F, U+202A–U+202E, U+2066–U+2069). Other format characters stay.
3. Both apply when the title is set by OSC 0 and 2, so `TerminalSession.title`, `onTitleChanged`, the saved titles (XTWINOPS 22, 23), and the title that esctest reads all see the same string. The length is cut after the characters are removed.

Sections 5 and 10.2 of [emulation.md](../design/emulation.md) describe the result.

**Why.**
- Apps naturally show the title outside the terminal (the Activity's title, the recents screen, a notification), through Binder, whose transactions are limited to about 1 MB for the process: a title of half a megabyte in several fields can throw `TransactionTooLargeException`. 1024 is far above what shells and editors set (a user, a host, and a path), and small for Binder.
- Cutting rather than dropping keeps the start of a long title, such as a long working directory, which is the part a user reads.
- Bidirectional controls and line breaks let the host make the title read differently from its characters, for example to spoof a file name or the host name that a user checks in the title. C1 controls are removed in the title whatever the parser does with OSC strings. Otherwise each app would have to know to clean the title up, against requirement 6.5 (safe defaults).
- The characters are removed rather than replaced, since a replacement character shows nothing more to the user. ZWJ and variation selectors are part of emoji, and the zero-width characters do not reorder or break the text, so they stay.

**Alternatives considered.**
- **Document the length and the characters, and leave the cleaning to the app**: the smallest change, but every app that shows the title would need the same code, and the default would still be unsafe.
- **Drop a title over the limit**, as for strings over the string limit: a long title would leave the previous one in place, which is more confusing than a cut one. The string limit drops the whole string because a cut control string could do something else (topic 2); a cut title is still a title.
- **A limit in `TerminalLimits`**: no app has needed another value, and a parameter can be added later ([0036](0036-adding-parameters.md)).
- **Remove all format characters (Cf)**: would break emoji sequences with ZWJ in titles, which some prompts use.

**Impact of reversing.** Remove the cleaning in `Emulator.setTitle` (or its caller for OSC 0 and 2), the row of the table in section 10.2 of emulation.md, the line in section 5, and the tests in `EmulationCoverageTest`. Apps that rely on the cleaned title would need their own.

## 6. OSC 52

1. `TerminalGate.handle` takes the events of one write of host output together, as `TerminalSession` collects them for each `feed`. Of the OSC 52 reads among them, only the first that the policy allows is answered with the clipboard; the others are replied to as empty, without reading the clipboard.
2. A clipboard text longer than `GatePolicy.maxClipboardReadLength` (by default the same as the limit on writes) is replied to as empty.
3. Both are replied to as empty, as an unreadable clipboard already is, rather than left unanswered.
4. Of the OSC 52 writes among the events of one write of host output, only the last that the policy allows is performed.
5. A write whose text has a control other than HT, LF, and CR (C0, DEL, C1), or a character with the Bidi_Control property, is refused as a whole. Other format and default-ignorable characters stay allowed.

Section 4.1 of [gate-policy.md](../design/gate-policy.md) describes the result.

**Why.**
- Items 1 to 3: when an app allows reads, a read is 9 bytes of host output, and answering each with the whole clipboard in base64 would let a host make the app queue replies far larger than its output: with 1 MiB on the clipboard, 139,811,300 bytes of replies to 900 bytes of output. `HostConnection` queues writes without blocking, so the replies would pile up in the app's memory, and each read calls the system clipboard (a Binder call, and on Android 12 and later a notice that the app read it). The reply to any other query is bounded by a constant per query; only the clipboard reply would be bounded by nothing the gate controls. With items 1 to 3, the replies to the reads of one write are at most one clipboard, of at most 256 KiB with the default limit (64 Ki code units, at most 3 bytes each in UTF-8, in base64), and a few bytes for each other read.
- Item 3: a program waiting for the reply does not wait for a timeout, and the reply stays a few bytes per read.
- Item 4: a host could otherwise change the system clipboard thousands of times from one write, each a Binder call and, on Android 13 and later, a preview that the system shows; the earlier writes would be replaced at once anyway. Like titles, only the latest value counts.
- Item 5: vtfx's own paste removes controls, but the user may paste into another app, where ESC or C1 could form sequences; and the preview that Android 13 and later shows, which [0006](0006-gate-defaults.md) relies on so that the user sees what the host put on the clipboard, shows bidi controls by reordering the text instead. Emoji sequences need U+200D and variation selectors, so other format and default-ignorable characters stay allowed.

**Alternatives considered.**
- **Only a limit on the length**: a host could still send many reads in one write, each replied to with up to the limit, so the replies would still grow far faster than the output.
- **At most one read answered with the clipboard per period of time**: bounds the rate whatever the host does, also when it sends each read in its own packet, but the gate and the session would have to read a clock. `config/boundaries.toml` allows neither `kotlin.time` nor `java.lang.System` in those modules, and relaxing it needs a human. A host that sends many small writes still gets at most one clipboard per write, each costing it a packet.
- **Remove the refused characters instead of refusing the write** (item 5): the text would then differ from what the host meant without anyone noticing; a host that copies plain text loses nothing.
- **Refuse every format and default-ignorable character, as URLs do** (item 5): would refuse common emoji.
- **Leave the other reads unanswered**: sends nothing, but programs such as vim and tmux wait for a reply until a timeout.
- **A fixed limit on the length, not in `GatePolicy`**: no new public API, but apps that allow reads cannot fit the limit to what their users copy, unlike the limit on writes.

**Impact of reversing.**
- Make `TerminalGate.handle` take one event again, and update `TerminalSession`, the tests, the esctest harness, and `GateFuzzer`.
- Remove `maxClipboardReadLength` and `DEFAULT_MAX_CLIPBOARD_READ_LENGTH` from `GatePolicy` and their entries in `api/`.
- Item 4: perform every write again. Item 5: write any text again.
- Revert section 4.1 of gate-policy.md and the tests in `AttackTest.Clipboard` and `AttackTest.ResourceExhaustion`.

## 7. The search's work per redraw

1. A redraw reads a bounded number of times, at most twice as many as reading the lines kept at its first read takes, plus a few, and leaves the rest to the next redraw.
2. A line that autowrap joins through more than 100 rows is searched in parts of 100 rows (`MAX_SEARCH_TEXT_ROWS`), and a match does not cross from one part to the next. For such lines, this replaces what [0047](0047-search.md) decided, that rows joined by autowrap are one text.

Section 13 of [compose-input.md](../design/compose-input.md) describes the result.

**Why.**
- Item 1: the search is kept up to date at each redraw after output, on the main thread, reading the lines a thousand at a time with the session's lock taken for each read ([0047](0047-search.md)). When the host writes faster than the reads go (`yes`, `cat` of a large log), each read starts at the oldest line still kept, which has moved on, and never reaches the screen; without another end, the main thread would stop, and with it drawing and the user's Ctrl-C. The bound is enough to read everything once without output in between, and to start over once when the lines are numbered again. The output that scrolls faster brings the next redraw; while it goes on, the matches of the newest lines may be missing, and when it stops, the last redraw reads up to the screen.
- Item 2: an update searches only the lines that entered the scrollback since and the screen, because a text that ended in the scrollback does not change; a text that goes on into the screen has not ended, so it would be read again from its first row at each update. Host output without line breaks (`base64 -w0`, minified JSON) makes one text of the whole scrollback and the screen, which each update would read and search with the session's lock held, in one read not bounded by the thousand lines. A part that ends in the scrollback does not change either, so it is searched once. 100 rows is a few screens: small next to the scrollback, while a match is lost only where it crosses the end of a part of a line at least that long.

**Alternatives considered.**
- **Read up to the screen as it was at the first read**: bounded while the lines keep their numbers, but output that clears the scrollback (ED 3) between the reads numbers them again and starts the search over at every read, so it needs a bound on the reads anyway.
- **Hold the session's lock for the whole update**: it always ends, but holds up the output thread for as long as reading the whole scrollback takes, which the reads a thousand lines at a time avoid.
- **Search on another thread**: see [0047](0047-search.md).
- **Keep searching a long line where the last update left it**, keeping the state of the matcher and the text of the line's rows in the scrollback, so that no match is lost: the text, the columns of its characters, and a match that goes on past the scrollback would all have to be continued across reads; much more state for matches at the ends of parts of very long lines.
- **Parts as long as a read (1,000 rows)**: fewer lost matches, but up to ten times the work at each redraw while such a line goes on, and the parts would depend on how many lines a caller reads at a time.

**Impact of reversing.** Change `readSearchLines` in `vtfx-compose`, `MAX_SEARCH_TEXT_ROWS`, `SearchIndex`, and `Terminal.searchLines` in `vtfx-core`, and section 13 of [compose-input.md](../design/compose-input.md).

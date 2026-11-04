# Bounds on what the app and the device give the display

- Status: Approved
- Date: 2026-11-04
- Related: Requirement 6.1, sections 3 and 10.1 of [compose-input.md](../design/compose-input.md), [0085](0085-compose-interaction.md), [0087](0087-public-api.md), [0100](0100-zoom-fling-loupe-and-scrolling.md)

## Background

The host's output is bounded by the limits of section 10 of [emulation.md](../design/emulation.md), but two inputs of `vtfx-compose` that come from elsewhere were not: the font size that the app gives in `TerminalStyle`, and the amount that the device reports for one wheel or touchpad event. A review found that `TerminalStyle(fontSize = 0.sp)` made a terminal of 300 by 200 dp tell the host 2048 columns, and that one wheel event with a delta of 10⁹ notches would make the main thread send 10⁹ reports. The human owner chose the two bounds below.

## 1. The font size

The constructor of `TerminalStyle` requires `fontSize` to be in sp, finite, and positive, and throws `IllegalArgumentException` otherwise (section 10.1 of [compose-input.md](../design/compose-input.md)).

**Why.** Such a size is a mistake of the app: it cannot be drawn, and a size of zero made cells of no size, so the grid took as many columns and rows as the session allows, which the host was told. Failing at once points at the mistake, where clamping would hide it behind a terminal that looks wrong.

**Alternatives considered.**
- **Clamp the cell size to at least a pixel**: the terminal would show thousands of columns that nobody can read, and the app would not learn why.
- **Leave it to the app, in the documentation**: the host was still told a size that nothing shows.

**Impact of reversing.** Remove the `init` block of `TerminalStyle` and its test; apps that relied on the exception get the old sizes.

## 2. The scroll events of one input event

One wheel event, or one move of a finger or a touchpad, sends at most as many wheel reports or arrow keys as the screen has rows (`scrollEventsFor` in `TerminalGestures.kt`; section 3 of [compose-input.md](../design/compose-input.md)). Scrolling through the scrollback is not bounded beyond its end, since it costs nothing per line.

**Why.** Real devices report about one notch an event, but the delta is a float from the device, and the display sent one report a notch, on the main thread, so a device that reports a huge move would hang the app. A host can do nothing useful with more than a screen of scrolling from one event, and the user can turn the wheel again.

**Alternatives considered.**
- **Bound the delta of an event to a constant**: a constant does not follow the size of the screen, which is what a program scrolls by.
- **Leave it**: a hang on the main thread is a crash for the user, whatever its cause.

**Impact of reversing.** Use the counts directly in the two `scroll` functions of `TerminalGestures.kt` and `TerminalMouse.kt`.

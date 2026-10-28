# Colors set by the host

- Status: Approved
- Date: 2026-10-27
- Related: Requirements 5.1, 6.1, and section 8 of [REQUIREMENTS.md](../REQUIREMENTS.md), sections 3 to 6 and 8.3 of [emulation.md](../design/emulation.md), section 3 of [gate-policy.md](../design/gate-policy.md), section 2 of [session-and-threads.md](../design/session-and-threads.md), [terminal-comparison-2026-10.md](../research/terminal-comparison-2026-10.md), [0079](0079-emulation-scope.md), [0083](0083-gate.md)

## Background

Tools that install color themes (base16-shell, tinted-theming, pywal) set the palette and the default colors with OSC 4, 10, 11, and 12, and reset them with OSC 104 and 110 to 112; neovim and tmux pass these on. Six of the eight terminals compared in [terminal-comparison-2026-10.md](../research/terminal-comparison-2026-10.md) support them. vtfx used to ignore them while answering color queries from the app's style, so a tool that set a color and then asked for it was told the old one. The colors still start from the app's `TerminalStyle` (requirement 8), and host output is untrusted (requirement 6.1).

## 1. The host may set the palette, the default colors, and the cursor color

The host sets the 256 indexed colors (OSC 4), the default foreground and background (OSC 10, 11), and the cursor color (OSC 12), and resets them (OSC 104, 110 to 112). The core keeps what the host set (`HostColors`), in the snapshot; the display draws those colors in place of its style's, and replies to color queries report them, falling back on the display's for the colors the host did not set. The handling is in sections 3 to 5 of [emulation.md](../design/emulation.md), the replies in section 3 of [gate-policy.md](../design/gate-policy.md). The app has no setting to refuse them.

**Why.** The colors the host sets are a part of the terminal state that tools rely on, and drawing them is what the tools that send them expect. They add nothing that SGR does not already allow: the host can draw text in the color of the background or in any truecolor with SGR, so setting the palette gives it no new way to hide or fake text. A reply carries only the numbers of a color, which the gate formats itself, so no text from the host reaches the host's input (section 3 of [gate-policy.md](../design/gate-policy.md)). The state is a fixed 259 colors, so it needs no resource limit. A setting for the app to refuse the colors is left out until an app needs one.

The core keeps the colors, rather than the display, so that there is one state that the drawing and the replies both read: a query is answered with the colors set before it in the same output, and two displays of one session draw the same colors. The query carries the color the host set (`TerminalQuery.Color.hostColor`), since the gate handles the queries of a write after the write, and a later OSC in the same write may change the color.

**Alternatives considered.**
- **Keep ignoring them** (as before): a tool sees its colors not take effect, and its queries contradict what it set.
- **A setting for the app to refuse them**: no app needs it yet. Adding it later is a new parameter of `TerminalSession` that makes the emulator ignore the settings.
- **Keep the colors in the display**: the display would also have to answer the queries, and a query would see the colors set by output that the display has not drawn yet, or not see those set before it in the same write.

**Impact of reversing.** Remove `HostColors`, its handling in `Emulator`, `hostColor` of `TerminalQuery.Color`, and `DisplayColors` in `vtfx-compose`, and move the settings back to section 4 of [emulation.md](../design/emulation.md) and the reasons table of topic 3 of [0079](0079-emulation-scope.md).

## 2. Which colors, which forms, and the resets follow xterm

- The colors are given as `rgb:R/G/B` (1 to 4 hexadecimal digits each, scaled to their range) or `#RGB` to `#RRRRGGGGBBBB` (the high bits of each component), as XParseColor reads them, and kept with 8 bits per component, as an X server with 24-bit color keeps them. X11 color names (`red`, `SlateGray`) and the other forms of XParseColor (`rgbi:`, the CIE spaces) are ignored.
- OSC 10 with several colors sets the dynamic colors from its own in turn, as xterm does; those after the cursor color (OSC 13 to 19 in xterm: the mouse pointer, Tektronix, and highlight colors) are ignored, and so are OSC 5, 13 to 19, and 105 to 119.
- DECSTR and RIS reset the indexed colors and keep the default and cursor colors, as xterm's reset does. The terminfo entry `xterm-256color` sends OSC 104 after RIS in `rs1`, so `reset` returns the palette in either case.
- A pair of OSC 4 with an index out of range or a color that does not parse is skipped, and the rest of the pairs still apply; xterm stops at it. OSC 104 stops at the first item that is not a number, as xterm does.

**Why.** Following xterm where the interpretation is unclear is topic 1 of [0079](0079-emulation-scope.md). Color names need a table of about 750 names, while the tools that set themes send `rgb:` or `#`. The colors that are ignored are those of features vtfx does not have (the Tektronix window, the mouse pointer) or that the app's style owns (the selection), which `TerminalColors.cursorText` also is: the host cannot set the text under the cursor, as section 8.3 of [emulation.md](../design/emulation.md) says. Skipping a bad pair of OSC 4 keeps the behavior that the queries had before the colors could be set.

**Alternatives considered.**
- **Accept X11 color names** (as kitty and Ghostty do): a table in the core for a form that the tools that set themes do not need.
- **Reset every color on RIS** (as alacritty does): differs from xterm, the reference, for no gain, since `reset` sends OSC 104.

**Impact of reversing.** Change `HostColors.parse` and the handling in `Emulator`, the tests in `EmulationCoverageTest.HostColorSettings`, and sections 3 to 5 of [emulation.md](../design/emulation.md).

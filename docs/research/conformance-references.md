# Proposal: references to conform to

> [!NOTE]
> A record of the proposal as written on 2026-10-12, kept for its reasoning; it is not kept up to date. It led to [decisions/0079](../decisions/0079-emulation-scope.md), and the references in use are in section 1 of [design/emulation.md](../design/emulation.md). Section 3 was not carried out as proposed: esctest2 runs for reports and is not a gate, DECRQCRA is not supported in any build (the esctest harness computes it in the test code), and no vttest goldens exist (section 11 of [testing.md](../design/testing.md)).

A proposal for "the references to conform to" in requirement 5.1.

## 1. Layers

Divide the references into layers, and decide the scope of each layer. Layers with smaller numbers take precedence.

| Layer | Target | Reference |
|---|---|---|
| 1. Syntax | Rules for splitting bytes into sequences | ECMA-48, and Paul Williams's DEC ANSI parser state machine (vt100.net/emu/dec_ansi_parser) |
| 2. Meaning of features from DEC | Cursor movement, margins, the pending state of end-of-line wrapping, origin mode, and so on | DEC STD 070, supplemented by manuals such as the VT510's |
| 3. xterm extensions | SGR 256 colors / truecolor, alternate screen (1049), bracketed paste (2004), SGR mouse (1006), focus events (1004), OSC 52 | xterm ctlseqs |
| 4. Individual extensions | synchronized output (2026), OSC 8 hyperlinks | Their respective proposed specifications (Contour's synchronized output specification, Egmont Koblinger's OSC 8 specification) |

## 2. Reasons

- **Syntax**: Williams's state machine is a de facto standard, also used by alacritty's vte, libvterm, and others. Its states are finite and clearly defined, which makes fuzzing and verifying "does not hang even on abnormal input" easy, and is advantageous for security too.
- **Features from DEC**: The original xterm tries to reproduce DEC's behavior, and where it deviates is stated in ctlseqs. So DEC STD 070 is treated as authoritative.
- **ctlseqs**: The most practical list of the sequences modern apps actually send. However, it does not describe behavior in full detail, so it is positioned as complementing layer 2.
- **The original xterm**: Thomas Dickey's xterm is a different thing from xterm.js. It can be treated as the implementation to consult when the interpretation of a reference is unclear.

## 3. Test oracles

| Tool | Nature | How to use |
|---|---|---|
| esctest2 | Automated tests, written against xterm | The main force of the gate. Requires sequences that query the terminal state (DECRQCRA and so on). DECRQCRA can be an information leak that allows guessing the screen contents, so it is enabled only in test builds. |
| vttest | Interactive; the screen is checked by eye | Screen dumps or screenshots checked by a human are fixed as goldens and used as regression tests. |

## 4. Recording deviations

Decisions to deviate from a reference are recorded one decision per file. An example is disabling OSC 52 reads for security reasons.

## 5. Relation to TERM

In practice, it is expected to announce `xterm-256color`. In that case, the lower bound of the supported scope becomes "the features the xterm-256color terminfo declares", which becomes a concrete criterion for deciding the supported scope in requirement 5.1.

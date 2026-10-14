# Module vtfx-session

A terminal session without a UI: the terminal, the input from the user, and the requests to the
host. The Compose UI of vtfx-compose displays it, and a JVM program can use it alone to emulate a
terminal, for example in the tests of a terminal app.

Create a [TerminalSession][com.google.vtfx.session.TerminalSession] connected to the host, feed it
what the host sends, read the screen with
[snapshot][com.google.vtfx.session.TerminalSession.snapshot], and type with
[sendKey][com.google.vtfx.session.TerminalSession.sendKey] and
[sendText][com.google.vtfx.session.TerminalSession.sendText].

# Package com.google.vtfx.session

The terminal session and its listener.

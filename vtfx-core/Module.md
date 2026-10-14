# Module vtfx-core

The terminal model, which interprets the output of the host. It cannot send anything to the host.

Its public API is the types that the session and the display share: the copy of the screen that
`TerminalSession.snapshot()` returns, keys, link patterns, and the limits on the resources that
host output can use.

# Package com.google.vtfx.core

The state of the screen, keys, link patterns, and limits.

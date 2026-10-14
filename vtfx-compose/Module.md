# Module vtfx-compose

The public API of vtfx, and the module that an app depends on. The other vtfx modules come with
it.

Create a [TerminalSession][com.google.vtfx.session.TerminalSession] of vtfx-session connected to the
host, feed it what the host sends, and display it with the
[Terminal][com.google.vtfx.compose.Terminal] composable, which takes the user's input from the
keyboard, the IME, and touch.

# Package com.google.vtfx.compose

The composable that displays a terminal session, and its appearance.

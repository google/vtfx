# Module vtfx-gate

The only way from vtfx to the host, and the decisions on the requests from the host with external
effects.

vtfx-gate holds the app's [HostConnection][com.google.vtfx.gate.HostConnection], which the app gives
to `TerminalSession`, and builds or checks every byte that vtfx sends to the host. [GatePolicy][com.google.vtfx.gate.GatePolicy] decides on the requests from the host with
external effects, such as setting the clipboard and opening URLs.

# Package com.google.vtfx.gate

The connection to the host, and the policy for requests with external effects.

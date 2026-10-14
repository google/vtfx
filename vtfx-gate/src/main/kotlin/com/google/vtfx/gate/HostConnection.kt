/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.vtfx.gate

/**
 * The connection to the host (SSH, a local PTY, ...), implemented by the app.
 *
 * The app gives it to `TerminalSession`, which hands it over to vtfx-gate at once, so that every
 * byte vtfx sends to the host is built or checked by vtfx-gate.
 */
public fun interface HostConnection {
    /**
     * Sends [bytes] to the host. [bytes] belongs to the connection from the call on: vtfx does not
     * read or reuse it afterwards, so the connection may queue it or overwrite it.
     *
     * Called on the thread whose call into vtfx made the input: with `TerminalSession`, the main
     * thread for the user's input, and the thread that feeds the host's output for replies to the
     * host's queries. The calls are serialized in the order of the input, with locks held that only
     * serialize them, so that sequences are not interleaved; no lock that guards the state of vtfx
     * is held.
     *
     * It must not block: queue the bytes and send them on another thread if the transport may
     * block. The user's input arrives on the main thread on Android, where network I/O is not
     * allowed, and the replies to the host's queries arrive inside `TerminalSession.feed`: while
     * one call blocks, the thread that reads the host's output waits for it too, and stops reading
     * the output that the transport may need to be read before it accepts more input.
     *
     * It must not call back into vtfx: sending more input or feeding output to the terminal throws
     * [IllegalStateException]. A host that answers at once, such as a local loopback, must hand its
     * output to another task.
     *
     * It must not throw. A failure of the transport is for the app to handle, for example by
     * closing the connection and telling the user; an exception propagates into vtfx as a
     * programming error.
     */
    public fun write(bytes: ByteArray)
}

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

import com.google.vtfx.core.InternalVtfxApi
import com.google.vtfx.core.KeyStroke
import com.google.vtfx.core.MouseEncoding
import com.google.vtfx.core.MouseTracking
import com.google.vtfx.core.TerminalModes
import com.google.vtfx.core.TerminalQuery

/**
 * The only holder of the [HostConnection]. Each method sends one kind of input, built or checked
 * here, so that no other module can send arbitrary bytes to the host.
 *
 * Thread-safe. Each call is sent as a whole, without interleaving with other calls.
 *
 * @param connection The connection to the host. vtfx sends to it only through this writer.
 * @suppress
 */
@InternalVtfxApi
public class HostWriter(private val connection: HostConnection) {
    private val lock = Any()

    /**
     * The thread inside [HostConnection.write], or null. Written with [lock] held; read without it
     * only to compare with the current thread, which is the only thread that can store itself.
     */
    @Volatile private var writingThread: Thread? = null

    /**
     * Sends text that the user typed or the IME committed. Line breaks become CR, and other control
     * characters are dropped (docs/design/ime-contract.md section 4.2).
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun sendText(text: String) {
        val bytes = OutboundText.encode(text)
        if (bytes.isNotEmpty()) write(bytes)
    }

    /**
     * Sends [count] DEL characters, which delete text already sent (ime-contract.md section 4.1).
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun sendDeletes(count: Int) {
        require(count in 1..MAX_DELETES) { "count out of range: $count" }
        write(ByteArray(count) { DEL })
    }

    /**
     * Sends [stroke] as xterm encodes it under [modes], for example with SS3 for the cursor keys in
     * application cursor mode (docs/decisions/0005).
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun sendKey(stroke: KeyStroke, modes: TerminalModes) {
        write(KeyEncoder.encode(stroke, modes))
    }

    /**
     * Sends pasted text. Control characters other than HT and line breaks are dropped, so that the
     * text cannot end a bracketed paste early (requirement 6.1). If [bracketed], the text is
     * wrapped in the bracketed paste markers.
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun sendPaste(text: String, bracketed: Boolean) {
        val bytes = OutboundText.encode(text)
        if (bytes.isEmpty()) return
        write(if (bracketed) PASTE_START + bytes + PASTE_END else bytes)
    }

    /**
     * Sends the reply to [query], built from the terminal state only. Color queries are answered
     * with [colors], or not at all without them.
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun sendReply(query: TerminalQuery, colors: ReportedColors? = null) {
        Replies.encode(query, colors)?.let { write(it) }
    }

    /**
     * Sends a mouse report, if the host asked for this kind of event with [tracking]. Positions
     * that [encoding] cannot express are not sent.
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun sendMouse(report: MouseReport, tracking: MouseTracking, encoding: MouseEncoding) {
        MouseReports.encode(report, tracking, encoding)?.let { write(it) }
    }

    /**
     * Sends a focus report (mode 1004): CSI I when focused, CSI O otherwise.
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun sendFocus(focused: Boolean) {
        // A copy, since the connection owns what it is given.
        write((if (focused) FOCUS_IN else FOCUS_OUT).copyOf())
    }

    /**
     * Whether the current thread is inside [HostConnection.write], where calling back into vtfx is
     * a programming error.
     *
     * @suppress
     */
    @InternalVtfxApi
    public fun isWritingOnCurrentThread(): Boolean = writingThread === Thread.currentThread()

    internal fun write(bytes: ByteArray) {
        synchronized(lock) {
            // The lock is reentrant, so a nested call would reach the connection inside its own
            // write.
            check(!isWritingOnCurrentThread()) { "HostConnection.write must not send to the host" }
            writingThread = Thread.currentThread()
            try {
                connection.write(bytes)
            } finally {
                writingThread = null
            }
        }
    }

    private companion object {
        const val DEL: Byte = 0x7F

        /** More DELs than this are a programming error: the IME history is far shorter. */
        const val MAX_DELETES = 65536
        val PASTE_START = "\u001b[200~".toByteArray(Charsets.US_ASCII)
        val PASTE_END = "\u001b[201~".toByteArray(Charsets.US_ASCII)
        val FOCUS_IN = "\u001b[I".toByteArray(Charsets.US_ASCII)
        val FOCUS_OUT = "\u001b[O".toByteArray(Charsets.US_ASCII)
    }
}

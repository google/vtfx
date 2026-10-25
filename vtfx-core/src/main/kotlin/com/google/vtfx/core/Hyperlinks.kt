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

package com.google.vtfx.core

// Not a data class, so that properties can be added without changing what apps compiled earlier
// call (docs/decisions/0087). Only vtfx makes it.
/**
 * A hyperlink set by OSC 8 (https://gist.github.com/egmontkob/eb114294efbcd5adb1944c9f3cb5feda).
 * [uri][Hyperlink.uri] is untrusted text from the host: show it to the user, and open it only with
 * `TerminalSession.openUrl` when they confirm. The session opens only the URL schemes that its
 * policy allows. Immutable, and equal to a hyperlink with the same values.
 */
public class Hyperlink
internal constructor(
    /** The URI that the link goes to, as the host sent it. */
    public val uri: String,
    /** The id parameter, which joins cells that are not adjacent into one link, or null. */
    public val id: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is Hyperlink && other.uri == uri && other.id == id

    override fun hashCode(): Int = 31 * uri.hashCode() + id.hashCode()

    override fun toString(): String = "Hyperlink(uri=$uri, id=$id)"
}

/**
 * The hyperlinks that cells refer to by number. Bounded by [capacity]: when full, links that no
 * cell refers to any more are removed.
 *
 * Finding the links in use visits every cell of the screens and the scrollback, so it is done at
 * most once per [capacity] / 2 requests for new links. Otherwise a host could make every short OSC
 * 8 sequence visit the whole scrollback (docs/decisions/0081).
 */
internal class HyperlinkTable(private val capacity: Int) {
    private val byId = HashMap<Int, Hyperlink>()
    private val byLink = HashMap<Hyperlink, Int>()
    private var nextId = 1

    /** The requests for new links since the links in use were last found. */
    private var requestsSinceScan = capacity

    operator fun get(id: Int): Hyperlink? = byId[id]

    /**
     * Returns the id of [link], adding it if needed. Returns 0 if the table is full even after
     * removing the links not in [inUse], or if it is full and [inUse] was called too recently.
     */
    fun intern(link: Hyperlink, inUse: () -> Set<Int>): Int {
        byLink[link]?.let {
            return it
        }
        requestsSinceScan++
        if (byId.size >= capacity) {
            if (requestsSinceScan < capacity / 2) return 0
            requestsSinceScan = 0
            val used = inUse()
            byId.keys.retainAll(used)
            byLink.values.retainAll(used)
            if (byId.size >= capacity) return 0
        }
        while (nextId == 0 || nextId in byId) nextId++
        val id = nextId++
        byId[id] = link
        byLink[link] = id
        return id
    }
}

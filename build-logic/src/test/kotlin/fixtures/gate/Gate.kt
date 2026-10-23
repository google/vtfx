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

package fixtures.gate

/** Stands for the connection to the host, whose members only the gate may use. */
interface HostConnection {
    val isOpen: Boolean

    fun write(bytes: ByteArray)
}

/** A subtype declared elsewhere, through which the members of [HostConnection] can be reached. */
interface BufferedConnection : HostConnection

/** Stands for the gate, which may use the connection. */
class Gate(private val connection: HostConnection) {
    fun send() {
        connection.write(byteArrayOf(1))
    }
}

/** A class that any module may extend, with a method that only the gate may call. */
open class Vibrator {
    fun vibrate() {}
}

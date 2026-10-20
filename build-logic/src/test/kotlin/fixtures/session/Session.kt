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

package fixtures.session

import fixtures.gate.BufferedConnection
import fixtures.gate.Gate
import fixtures.gate.HostConnection

/** Only names the connection as a type, and hands it over to the gate. */
class HandsOver(connection: HostConnection) {
    val gate = Gate(connection)
}

class CallsWrite(private val connection: HostConnection) {
    fun send() {
        connection.write(byteArrayOf(1))
    }
}

class CallsWriteThroughSubtype(private val connection: BufferedConnection) {
    fun send() {
        connection.write(byteArrayOf(1))
    }
}

class ReadsProperty(private val connection: HostConnection) {
    fun isOpen(): Boolean = connection.isOpen
}

class ReferencesWrite(private val connection: HostConnection) {
    fun writer(): (ByteArray) -> Unit = connection::write
}

class CallsWriteInLambda(private val connection: HostConnection) {
    fun sender(): Runnable = Runnable { connection.write(byteArrayOf(1)) }
}

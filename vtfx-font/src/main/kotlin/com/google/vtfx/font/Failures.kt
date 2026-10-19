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

package com.google.vtfx.font

import com.google.vtfx.font.gate.InvalidFontException

// The verifier in vtfx-font-gate has its own, so that it shares no code with the readers.

internal fun malformed(message: String): Nothing =
    throw InvalidFontException(InvalidFontException.Kind.Malformed, message)

internal fun unsupported(message: String): Nothing =
    throw InvalidFontException(InvalidFontException.Kind.Unsupported, message)

internal fun tooLarge(message: String): Nothing =
    throw InvalidFontException(InvalidFontException.Kind.TooLarge, message)

internal fun tooComplex(message: String): Nothing =
    throw InvalidFontException(InvalidFontException.Kind.TooComplex, message)

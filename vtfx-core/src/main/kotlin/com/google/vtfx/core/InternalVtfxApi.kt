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

/**
 * Marks declarations that are public only so that the vtfx modules can use each other's
 * implementation, such as the terminal model and the IME editing model. They are not part of the
 * public API: they may change or disappear in any version, and the public API compatibility check
 * does not cover them.
 *
 * The files of the vtfx modules that use them opt in with `@file:OptIn(InternalVtfxApi::class)`,
 * not with a compiler option, so that the modules compile with the compiler's default options in
 * any build system. Apps must not opt in.
 */
@RequiresOptIn(
    message = "This is an implementation detail shared between the vtfx modules, not public API.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class InternalVtfxApi

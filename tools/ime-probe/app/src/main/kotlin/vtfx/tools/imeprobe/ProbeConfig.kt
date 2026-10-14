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

package vtfx.tools.imeprobe

import android.content.Intent
import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.json.JSONObject

/** How much already-committed text the editor exposes to the IME. */
enum class SurroundingPolicy {
    /** Only uncommitted (composing) text is visible. Committed text is removed immediately. */
    NONE,

    /**
     * Committed text stays visible until a non-text key (Enter, arrows, ...) resets it. Deleting
     * committed text is translated to DEL bytes.
     */
    HISTORY,
}

/**
 * Probe configuration, read from intent extras so that runs can be scripted with
 * `adb shell am start -n vtfx.tools.imeprobe/.MainActivity --es surrounding history ...`.
 */
data class ProbeConfig(
    val label: String,
    val surrounding: SurroundingPolicy,
    val inputTypeName: String,
    val noPersonalizedLearning: Boolean,
    /** Whether to use a stock EditText instead of the probe editor, as a baseline. */
    val useEditText: Boolean,
    /** Whether to reject selection changes other than a collapsed cursor at the end. */
    val rejectSelection: Boolean,
) {
    val inputType: Int
        get() = when (inputTypeName) {
            "text" -> InputType.TYPE_CLASS_TEXT
            "text_autocorrect" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            // What a typical chat or notes text field uses.
            "text_typical" -> InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            "text_multiline" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            "text_nosuggest" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            "visible_password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            "null" -> InputType.TYPE_NULL
            else -> throw IllegalArgumentException("Unknown inputType: $inputTypeName")
        }

    val imeOptions: Int
        get() {
            var options = EditorInfo.IME_ACTION_NONE or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI
            if (noPersonalizedLearning) {
                options = options or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
            return options
        }

    fun toJson(): JSONObject = JSONObject()
        .put("label", label)
        .put("surrounding", surrounding.name.lowercase())
        .put("inputType", inputTypeName)
        .put("noPersonalizedLearning", noPersonalizedLearning)
        .put("useEditText", useEditText)
        .put("rejectSelection", rejectSelection)

    companion object {
        fun fromIntent(intent: Intent): ProbeConfig {
            val config = ProbeConfig(
                label = intent.getStringExtra("label") ?: "",
                surrounding = SurroundingPolicy.valueOf(
                    (intent.getStringExtra("surrounding") ?: "none").uppercase()
                ),
                inputTypeName = intent.getStringExtra("inputType") ?: "text",
                noPersonalizedLearning = intent.getBooleanExtra("noLearning", true),
                useEditText = intent.getBooleanExtra("editText", false),
                rejectSelection = intent.getBooleanExtra("rejectSelection", false),
            )
            // Validate eagerly so that a typo fails at launch rather than at first focus.
            config.inputType
            return config
        }
    }
}

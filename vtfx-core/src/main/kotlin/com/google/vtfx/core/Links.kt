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

// See docs/decisions/0019.
/**
 * Text that the display shows as a link, besides OSC 8 hyperlinks: the matches of a regular
 * expression, each linking to the URL that a template makes from it, or the URLs written in the
 * text ([Urls]).
 *
 * The text comes from the host. Each value put into the template is percent-encoded, so that the
 * matched text cannot change the host or add parts to the URL: with `https://crbug.com/{1}`, text
 * that the regular expression lets through as group 1 stays in one path segment, even if it has
 * `/`, `@`, or `?`. Write a group for each part of the URL instead, for example
 * `https://github.com/{1}/{2}`. A value of `.` or `..` is kept as it is, so it makes a dot segment,
 * which opening the URL removes, with the segment before it for `..`.
 *
 * The regular expression runs on text from the host each time the display changes, on the main
 * thread when the `Terminal` composable displays it, and vtfx does not bound its work: Android's
 * regular expressions cannot be stopped once started. The app must give patterns that take time
 * linear in the text. Avoid nested or overlapping repetition, such as `(a|aa)+b` or `(\w+)*x`, on
 * which the host can make matching take exponential time; prefer patterns such as `\bcrbug/(\d+)`,
 * and character classes to alternations, such as `[\w-]+`.
 *
 * Two patterns are equal if they have the same regular expression, options, and template. A pattern
 * is immutable.
 */
public class LinkPattern private constructor(private val matcher: TextMatcher) {
    /**
     * Links the matches of `regex` to `urlTemplate`, in which `{0}` is replaced by the whole match
     * and `{n}` by group n (an empty string if the group did not match), percent-encoded. Braces
     * may appear only in placeholders.
     *
     * @throws IllegalArgumentException if the template has other braces or refers to a group that
     *   `regex` does not have.
     */
    public constructor(
        regex: Regex,
        urlTemplate: String,
    ) : this(TemplateMatcher(regex, urlTemplate))

    /** Calls [onMatch] for each match in [text], in order, with its range and URL. */
    internal fun find(text: String, onMatch: (start: Int, end: Int, url: String) -> Unit) =
        matcher.find(text, onMatch)

    override fun equals(other: Any?): Boolean = other is LinkPattern && other.matcher == matcher

    override fun hashCode(): Int = matcher.hashCode()

    override fun toString(): String = "LinkPattern($matcher)"

    public companion object {
        /**
         * The `http` and `https` URLs written in the text. The URL is the text itself, without the
         * punctuation that usually ends a sentence and closing brackets without an opening one.
         * Only ASCII characters allowed in URLs are taken.
         */
        public val Urls: LinkPattern = LinkPattern(UrlMatcher)
    }
}

private sealed interface TextMatcher {
    fun find(text: String, onMatch: (start: Int, end: Int, url: String) -> Unit)
}

/** Finds `http` and `https` URLs in linear time. */
private data object UrlMatcher : TextMatcher {
    private val schemes = listOf("https://", "http://")

    override fun find(text: String, onMatch: (start: Int, end: Int, url: String) -> Unit) {
        var index = 0
        while (index < text.length) {
            val start = text.indexOf("http", index, ignoreCase = true)
            if (start < 0) return
            index = start + 1
            if (start > 0 && text[start - 1].isLetterOrDigit()) continue
            val scheme =
                schemes.firstOrNull { text.startsWith(it, start, ignoreCase = true) } ?: continue
            val rest = start + scheme.length
            var end = rest
            while (end < text.length && isUrlCharacter(text[end])) end++
            end = trimEnd(text, rest, end)
            if (end == rest) continue
            onMatch(start, end, text.substring(start, end))
            index = end
        }
    }

    /** RFC 3986's unreserved and reserved characters, and `%`. */
    private fun isUrlCharacter(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-._~:/?#[]@!$&'()*+,;=%"

    /** Drops the punctuation that ends a sentence and unbalanced closing brackets. */
    private fun trimEnd(text: String, start: Int, end: Int): Int {
        // Opening minus closing brackets in start until trimmed, kept as the end is trimmed so
        // that a long run of brackets takes linear time.
        var parentheses = 0
        var brackets = 0
        for (i in start until end) {
            when (text[i]) {
                '(' -> parentheses++
                ')' -> parentheses--
                '[' -> brackets++
                ']' -> brackets--
            }
        }
        var trimmed = end
        while (trimmed > start) {
            when (text[trimmed - 1]) {
                '.',
                ',',
                ';',
                ':',
                '!',
                '?',
                '\'' -> {}
                ')' -> if (parentheses < 0) parentheses++ else break
                ']' -> if (brackets < 0) brackets++ else break
                else -> break
            }
            trimmed--
        }
        return trimmed
    }
}

private class TemplateMatcher(private val regex: Regex, template: String) : TextMatcher {
    private val parts: List<TemplatePart> =
        parseTemplate(template, regex.toPattern().matcher("").groupCount())
    private val template = template

    override fun find(text: String, onMatch: (start: Int, end: Int, url: String) -> Unit) {
        val matcher = regex.toPattern().matcher(text)
        while (matcher.find()) {
            if (matcher.start() == matcher.end()) continue
            val url = buildString {
                for (part in parts) {
                    when (part) {
                        is TemplatePart.Literal -> append(part.text)
                        is TemplatePart.Group ->
                            appendPercentEncoded(matcher.group(part.index) ?: "")
                    }
                }
            }
            onMatch(matcher.start(), matcher.end(), url)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is TemplateMatcher &&
            other.regex.pattern == regex.pattern &&
            other.regex.options == regex.options &&
            other.template == template

    override fun hashCode(): Int =
        (regex.pattern.hashCode() * 31 + regex.options.hashCode()) * 31 + template.hashCode()

    override fun toString(): String = "${regex.pattern} -> $template"
}

private sealed interface TemplatePart {
    class Literal(val text: String) : TemplatePart

    class Group(val index: Int) : TemplatePart
}

private fun parseTemplate(template: String, groupCount: Int): List<TemplatePart> {
    val parts = mutableListOf<TemplatePart>()
    var index = 0
    while (index < template.length) {
        val open = template.indexOf('{', index)
        val literalEnd = if (open < 0) template.length else open
        val literal = template.substring(index, literalEnd)
        require('}' !in literal) { "Unmatched '}' in the URL template: $template" }
        if (literal.isNotEmpty()) parts += TemplatePart.Literal(literal)
        if (open < 0) break
        val close = template.indexOf('}', open)
        require(close > open) { "Unmatched '{' in the URL template: $template" }
        val group = template.substring(open + 1, close).toIntOrNull()
        require(group != null && group in 0..groupCount) {
            "The URL template refers to no group of the pattern: $template"
        }
        parts += TemplatePart.Group(group)
        index = close + 1
    }
    return parts
}

/** Appends [value] as UTF-8, with every byte other than RFC 3986's unreserved characters as %XX. */
private fun StringBuilder.appendPercentEncoded(value: String) {
    for (byte in value.encodeToByteArray()) {
        val c = (byte.toInt() and 0xFF).toChar()
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-._~") {
            append(c)
        } else {
            append('%')
            append(HEX_DIGITS[c.code shr 4])
            append(HEX_DIGITS[c.code and 0xF])
        }
    }
}

private const val HEX_DIGITS = "0123456789ABCDEF"

/**
 * A link that a [LinkPattern] found on a display.
 *
 * @property text The matched text, which may go on beyond the display.
 * @property url The URL that [pattern] made of [text].
 * @property pattern The pattern that matched.
 * @property spans The cells of the link on the display, a run on each row it spans, from the top.
 * @suppress
 */
@InternalVtfxApi
public class FoundLink(
    public val text: String,
    public val url: String,
    public val pattern: LinkPattern,
    public val spans: List<CellSpan>,
) {
    /** Whether the link covers the cell at [column], [row]. */
    public fun contains(column: Int, row: Int): Boolean = spans.any {
        it.row == row && column in it.start until it.end
    }
}

/**
 * The cells [start] until [end] of [row].
 *
 * @suppress
 */
@InternalVtfxApi
public class CellSpan(public val row: Int, public val start: Int, public val end: Int)

/**
 * Finds the links of [patterns] on a display. Rows joined by autowrap are matched as one text,
 * without the last cell that autowrap leaves blank when a wide character does not fit. Where
 * matches overlap, the earlier pattern in [patterns] wins. Then a match that covers a cell with an
 * OSC 8 hyperlink, or a concealed cell (SGR 8), is not a link, and the matches that it won over
 * stay dropped: overlaps are decided on the text alone, so a later pattern does not make a link
 * inside an earlier pattern's match (docs/decisions/0019).
 *
 * A line that the display cuts is matched with its rows beyond the display that the snapshot keeps,
 * a screen each way. Where a line goes on beyond those too, a match that reaches the cut is not a
 * link, since the text beyond could change it.
 *
 * Keeps the links of the rows it saw in the last call, by the rows' content, since a display mostly
 * shows the same lines from one frame to the next. Not thread-safe.
 *
 * @suppress
 */
@InternalVtfxApi
public class LinkFinder(private val patterns: List<LinkPattern>) {
    /**
     * Rows joined by autowrap, equal to other rows with the same content. [isCutAbove] and
     * [isCutBelow] tell whether the line goes on beyond them.
     */
    private class Rows(
        val lines: List<LineSnapshot>,
        val isCutAbove: Boolean,
        val isCutBelow: Boolean,
    ) {
        private val hash =
            lines.fold(if (isCutAbove) 1 else 0) { hash, line ->
                hash * 31 + line.contentHashCode()
            } * 2 + if (isCutBelow) 1 else 0

        override fun equals(other: Any?): Boolean =
            other is Rows &&
                other.hash == hash &&
                other.isCutAbove == isCutAbove &&
                other.isCutBelow == isCutBelow &&
                other.lines.size == lines.size &&
                lines.indices.all { other.lines[it].contentEquals(lines[it]) }

        override fun hashCode(): Int = hash
    }

    private class TextMatch(val start: Int, val end: Int, val url: String, val pattern: LinkPattern)

    /** The links of each [Rows], with rows counted from the first of them. */
    private var previous = HashMap<Rows, List<FoundLink>>()
    private var current = HashMap<Rows, List<FoundLink>>()

    /** Returns the links on [snapshot], ordered by their first cell. */
    public fun find(snapshot: TerminalSnapshot): List<FoundLink> {
        val links = mutableListOf<FoundLink>()
        if (patterns.isNotEmpty()) {
            // The display, with the rows of the lines it cuts.
            val above = snapshot.linesAbove.size
            val lines = snapshot.linesAbove + snapshot.lines + snapshot.linesBelow
            var row = 0
            while (row < lines.size) {
                val first = row
                while (row < lines.size - 1 && lines[row].isWrapped) row++
                val rows =
                    Rows(
                        lines.subList(first, row + 1),
                        isCutAbove = first == 0 && snapshot.isCutAbove,
                        isCutBelow = row == lines.lastIndex && snapshot.isCutBelow,
                    )
                val found = current[rows] ?: previous[rows] ?: findInRows(rows)
                current[rows] = found
                for (link in found) {
                    val spans =
                        link.spans.mapNotNull { span ->
                            val displayRow = span.row + first - above
                            if (displayRow in 0 until snapshot.rows)
                                CellSpan(displayRow, span.start, span.end)
                            else null
                        }
                    if (spans.isNotEmpty())
                        links += FoundLink(link.text, link.url, link.pattern, spans)
                }
                row++
            }
        }
        previous = current
        current = HashMap()
        return links
    }

    /** Finds the links in [rows], with rows counted from the first. */
    private fun findInRows(rows: Rows): List<FoundLink> {
        val text = LineText(rows.lines, trimEnd = false)
        val string = text.text
        return match(string)
            .filter {
                !(rows.isCutAbove && it.start == 0) && !(rows.isCutBelow && it.end == string.length)
            }
            .mapNotNull { toLink(rows.lines, text, it) }
    }

    /**
     * Returns the matches of [patterns] in [text], ordered, where an earlier pattern wins an
     * overlap.
     */
    private fun match(text: String): List<TextMatch> {
        // The matches of the patterns so far, ordered and without overlaps. A pattern finds its
        // matches in order without overlaps, so one pass over the kept ones checks and merges them
        // all, and the work is linear in the number of matches.
        var kept = emptyList<TextMatch>()
        for (pattern in patterns) {
            val merged = ArrayList<TextMatch>(kept.size)
            var next = 0
            var previousEnd = 0
            pattern.find(text) { start, end, url ->
                check(start >= previousEnd) { "matches out of order" }
                previousEnd = end
                // The kept matches before next end at or before start.
                while (next < kept.size && kept[next].end <= start) merged += kept[next++]
                if (next == kept.size || end <= kept[next].start)
                    merged += TextMatch(start, end, url, pattern)
            }
            merged.addAll(kept.subList(next, kept.size))
            kept = merged
        }
        return kept
    }

    /**
     * Returns the link of [match], or null if it covers a hyperlink or a concealed cell (SGR 8),
     * whose text the display does not show: the URL would then differ from what the user sees.
     */
    private fun toLink(lines: List<LineSnapshot>, text: LineText, match: TextMatch): FoundLink? {
        val spans = text.spans(match.start, match.end)
        for (span in spans) {
            val line = lines[span.row]
            if (
                (span.start until span.end).any {
                    line.hyperlinkAt(it) != null || line.styleAt(it).isInvisible
                }
            ) {
                return null
            }
        }
        return FoundLink(
            text.text.substring(match.start, match.end),
            match.url,
            match.pattern,
            spans,
        )
    }
}

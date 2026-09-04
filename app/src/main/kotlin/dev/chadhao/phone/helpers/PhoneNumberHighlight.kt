package dev.chadhao.phone.helpers

/**
 * Shared phone-number highlight layer (真机反馈第四批).
 *
 * Problem being fixed: the number line used to be highlighted with a **literal** `contains(query)`
 * on the formatted display string (e.g. `136 0730 6251`). As soon as `formatPhoneNumbers` inserts
 * spaces, a ≥4-digit pure-digit query like `1360730` can never be found in `136 0730 6251`, so the
 * whole highlight branch was skipped (only 3-digit prefixes like `136` happened to match).
 *
 * This object replaces that with a two-step mapping:
 *  1. normalise both the query and the *display* string to pure digits (`isDigit()` only);
 *  2. locate the query inside the display digits and map the matched digit range back to character
 *     offsets of the original display string, producing `IntRange`s that naturally span any inner
 *     separators (space / hyphen / `+86` tail) while never painting a non-matching prefix such as
 *     `+86 `.
 *
 * Because the mapping is built from whatever display string is actually shown, both states of
 * `formatPhoneNumbers` (formatted vs raw) are correct without any flag: each state maps its own
 * display string (AC-10 in ARCH-真机反馈第四批).
 *
 * ### Coverage matrix (query → display → painted segment)
 * | query      | display          | painted |
 * |------------|------------------|---------|
 * | `136`      | `136 0730 6251`  | `136` |
 * | `1360`     | `136 0730 6251`  | `136 0` (space inside span) |
 * | `13607`    | `136 0730 6251`  | `136 07` |
 * | `1360730`  | `136 0730 6251`  | `136 0730` |
 * | `1360730`  | `+86 136 0730 2499` | `136 0730` (`+86 ` never painted) |
 * | `1360730625` | `136 0730 6251` | `136 0730 625` (last digit excluded) |
 * | `13607306251` | `136 0730 6251` | whole string |
 * | `79246`    | `135 7924 6800`  | `7924 6` (non-prefix match, middle group) |
 * | `10086` / `10` | `10086`     | whole / `10` (short numbers unchanged) |
 * | `1360730`  | `136 0730-6251` (raw stored separators, FMT off) | `136 0730` |
 * | `1360730`  | `13607306251` (raw, FMT off) | `1360730` |
 *
 * Notes:
 * - The query itself may contain formatted spaces (`136 0730` from the dialpad input box); it is
 *   normalised to pure digits before matching, so no double-space misalignment can occur.
 * - Only the **first** occurrence is painted by default (`highlightAll = false`), matching the
 *   previous `indexOf`-based behaviour and AC-13. `highlightAll = true` is available as an opt-in.
 * - Rendering reuses [applyRangeHighlight], which skips out-of-range/malformed ranges safely.
 */
object PhoneNumberHighlight {

    /**
     * Keeps only digit characters of [s]. Used as the single normalisation entry point for both
     * the display number and the query, so `+`, spaces, hyphens, NBSP etc. are all ignored.
     */
    fun pureDigits(s: String): String = s.filter { it.isDigit() }

    /**
     * True if the digit-normalised [value] contains the digit-normalised [query].
     * An empty digit query (e.g. a name/letter search) imposes no digit constraint.
     * Used to pick the right phone number among a contact's multiple numbers.
     */
    fun containsNumberDigits(value: String, query: String): Boolean {
        val queryDigits = pureDigits(query)
        if (queryDigits.isEmpty()) return true
        return pureDigits(value).contains(queryDigits)
    }

    /**
     * Maps every digit of [display] to its character offset; `offsets[d]` is the char index of the
     * `d`-th digit (0-based) inside [display]. Non-digit characters are simply not mapped.
     */
    private fun digitOffsets(display: String): List<Int> {
        val offsets = ArrayList<Int>(display.length)
        display.forEachIndexed { index, ch -> if (ch.isDigit()) offsets.add(index) }
        return offsets
    }

    /**
     * Locates the digit-normalised [query] inside [display] and returns character ranges of
     * [display] covering the matched segment(s) *including* inner separators (space/hyphen/`+86`
     * tail) but excluding any non-matching prefix such as `+86 `. Returns null when there is no
     * match (callers then keep the plain display text). With [highlightAll] only the first match
     * is returned, matching the pre-existing `highlightAll = false` semantics.
     */
    fun findNumberMatchRanges(display: String, query: String, highlightAll: Boolean = false): List<IntRange>? {
        val queryDigits = pureDigits(query)
        if (queryDigits.isEmpty()) return null

        val displayDigits = pureDigits(display)
        if (displayDigits.isEmpty() || queryDigits.length > displayDigits.length) return null

        val offsets = digitOffsets(display)
        val ranges = ArrayList<IntRange>(1)
        var fromDigit = 0
        while (true) {
            val digitStart = displayDigits.indexOf(queryDigits, fromDigit)
            if (digitStart < 0) break
            val digitEnd = digitStart + queryDigits.length - 1
            val charStart = offsets[digitStart]
            val charEnd = offsets[digitEnd] + 1
            ranges.add(charStart..charEnd - 1)
            if (!highlightAll) break
            fromDigit = digitEnd + 1
        }
        return if (ranges.isEmpty()) null else ranges
    }

    /**
     * Convenience wrapper: paints [query] matches on [display] with [color] or returns [display]
     * unchanged when there is nothing to highlight. The returned [CharSequence] is safe to set on
     * a TextView.
     */
    fun highlightNumberMatch(display: String, query: String, color: Int, highlightAll: Boolean = false): CharSequence {
        val ranges = findNumberMatchRanges(display, query, highlightAll) ?: return display
        return applyRangeHighlight(display, ranges, color)
    }
}
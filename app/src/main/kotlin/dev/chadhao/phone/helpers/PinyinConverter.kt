package dev.chadhao.phone.helpers

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

/**
 * Thin wrapper around pinyin4j.
 *
 * Responsibilities:
 *  - expose all readings of a Han char (polyphonic chars like 曾/乐/重 return every reading, no-tone, lowercase)
 *  - cache per-char conversions so building a contact index over thousands of contacts is cheap
 *  - detect whether a text/char is Chinese (has any pinyin reading)
 *
 * ü is rendered as "v" (e.g. 绿 -> "lv") so all output only contains 'a'..'z'.
 */
object PinyinConverter {

    private val outputFormat = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
        vCharType = HanyuPinyinVCharType.WITH_V
    }

    private val cache = HashMap<Char, List<String>>()

    /** Returns every distinct no-tone reading of [char]; empty list for non-Chinese chars. */
    @Synchronized
    fun getReadings(char: Char): List<String> {
        cache[char]?.let { return it }
        val readings = try {
            PinyinHelper.toHanyuPinyinStringArray(char, outputFormat)
                ?.map { it.lowercase() }
                ?.distinct()
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        cache[char] = readings
        return readings
    }

    fun isChineseChar(char: Char): Boolean = getReadings(char).isNotEmpty()

    fun containsChinese(text: String): Boolean = text.any { isChineseChar(it) }
}

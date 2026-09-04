package dev.chadhao.phone.helpers

import com.goodwy.commons.models.contacts.Contact
import java.text.Normalizer

/**
 * In-memory phonetic (pinyin T9) search index over the full contact list.
 *
 * Design doc: 功能调整-检索增强详细设计.md §1-§3
 *  - every contact is parsed into ordered positions (Han char => one syllable position,
 *    Latin word => one word position).
 *  - name-tier matching uses a SINGLE canonical candidate per contact (R5 ruling):
 *      * canonicalInitials : each char keeps ONLY its primary reading (the one shown in the
 *        list abbreviation, == position.initials.first()), e.g. 陈伟 -> "cw" -> digits "29".
 *      * canonicalFull     : same primary reading full syllables, e.g. 陈伟 -> "chenwei".
 *    Polyphonic readings are deliberately NOT expanded into the name tier anymore, otherwise
 *    朝朝 (displayed CC -> "22") could sneak in via chao+zhao cross readings as "29". All
 *    readings are still consulted only by highlightRanges() as a fallback (宁多标,不漏标).
 *  - matching accepts subsequences (jump matching): "29" matches "529" as "b..w" of 李不为.
 *  - scoring follows §1.4: number prefix/exact > initials prefix/exact > full prefix
 *    > initials subsequence > full subsequence > number contains; + family(given) start bonus.
 *    Name-tier hits are shifted above every phone-number hit (NAME_MATCH_BONUS).
 *
 * All query methods are thread-safe against concurrent rebuilds and should be called from a
 * background thread (records are rebuilt lazily on first use / when the source list changes).
 */
object ContactSearchIndex {

    data class ContactMatch(val contact: Contact, val score: Int)

    private data class Position(val initials: List<String>, val syllables: List<String>)

    private data class Candidate(val letters: String, val digits: String)

    private data class Parts(
        val positions: List<Position>,
        val familySyllableCount: Int,
        val isChineseName: Boolean,
    )

    private data class PhoneticRecord(
        val contactId: Int,
        val abbr: String,
        val isChineseName: Boolean,
        val familySyllableCount: Int,
        val totalSyllableCount: Int,
        // R5: each list holds exactly one canonical candidate (primary reading per char).
        val initialCandidates: List<Candidate>,
        val fullCandidates: List<Candidate>,
        val phoneDigits: List<String>,
    )

    /** Identity marker of the last rebuilt source list (avoids rebuilding on every keystroke). */
    @Volatile
    private var sourceList: List<Contact>? = null

    /** Immutable copy of the source contacts. Queries iterate this, never the (possibly mutated) source list. */
    @Volatile
    private var snapshot: List<Contact> = emptyList()

    @Volatile
    private var records: Map<Int, PhoneticRecord> = emptyMap()

    /**
     * Name matches always outrank phone-number matches (requirement: 姓名优先级 > 号码).
     * Number scoring keeps its own 0..999 range; every name (initials/full pinyin) match
     * is shifted far above it.
     */
    private const val NAME_MATCH_BONUS = 100_000

    private val COMPOUND_SURNAMES = setOf(
        "欧阳", "司马", "上官", "诸葛", "司徒", "夏侯", "令狐", "皇甫",
        "公孙", "长孙", "慕容", "鲜于", "宇文", "尉迟", "申屠", "独孤",
        "轩辕", "南宫", "呼延", "东方", "百里", "东郭", "南门", "西门",
        "闻人", "淳于", "公羊", "澹台", "公冶", "宗政", "濮阳", "单于",
    )

    /** Rebuilds the whole index from [contacts]. Cheap enough to run on a background thread. */
    fun rebuild(contacts: List<Contact>) {
        val contactsCopy = ArrayList<Contact>(contacts.size)
        contactsCopy.addAll(contacts)

        val newRecords = HashMap<Int, PhoneticRecord>()
        for (contact in contactsCopy) {
            val record = buildRecord(contact) ?: continue
            newRecords[record.contactId] = record
        }
        synchronized(this) {
            records = newRecords
            snapshot = contactsCopy
            sourceList = contacts
        }
    }

    /** Pinyin abbreviation (e.g. "LBW") for a contact id, if it is a Chinese name. */
    fun abbreviationFor(contactId: Int): String? = records[contactId]?.abbr?.takeIf { it.isNotEmpty() }

    /**
     * True when [score] belongs to the name (pinyin initials/full) tier, false when it belongs to
     * the phone-number tier. Used by callers that group merged dialpad results so that pinyin-name
     * hits are always shown before pure number-substring hits.
     */
    fun isNameTierScore(score: Int): Boolean = score >= NAME_MATCH_BONUS

    /**
     * Computes character ranges of a display [name] whose pinyin initials/full syllables match [query]
     * (letters or T9 digits). Used to paint the *matched Chinese characters* blue when the typed query
     * does not literally appear inside the name. Returns null when the query cannot be aligned to the name.
     *
     * Only pure-character positions are reported: a matched Han char covers exactly itself; a matched
     * Latin word covers the whole word. Ranges are relative to [name].
     *
     * Polyphonic Han chars contribute ALL their readings to the alignment stream (a char keeps one
     * position, but every reading's letters are concatenated on it). This is an intentional
     * highlight-only fallback: since R5 the index matches names through the single canonical
     * (primary reading) candidate, but a matched char is painted regardless of which reading aligned,
     * so the broad alignment avoids missing a highlight. It can occasionally over-mark a whole char
     * when a query spans two readings of the same character; that is the accepted trade-off (宁多标,不漏标).
     */
    fun highlightRanges(name: String, query: String, isDigitsQuery: Boolean): List<IntRange>? {
        val q = if (isDigitsQuery) query.filter { it.isDigit() } else query.lowercase()
        if (name.isEmpty() || q.isEmpty() || name.length > 64) return null

        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        val initialLetters = ArrayList<String>()
        val fullLetters = ArrayList<String>()

        var i = 0
        while (i < name.length) {
            val char = name[i]
            val readings = PinyinConverter.getReadings(char)
            if (readings.isNotEmpty()) {
                starts.add(i)
                ends.add(i + 1)
                // All readings, not just the first one (highlight fallback): a name matched through
                // its canonical primary reading must have the right Han char painted regardless of
                // pinyin order, and this loose alignment guarantees the highlight is never missed.
                initialLetters.add(readings.map { it.substring(0, 1) }.distinct().joinToString(""))
                fullLetters.add(readings.joinToString(""))
                i++
            } else if (char.isLetter()) {
                val wordStart = i
                val wordBuilder = StringBuilder()
                while (i < name.length) {
                    val current = name[i]
                    if (PinyinConverter.isChineseChar(current)) break
                    if (!current.isLetter()) break
                    wordBuilder.append(current)
                    i++
                }
                val word = normalizeLatinWord(wordBuilder.toString())
                if (word.isNotEmpty()) {
                    starts.add(wordStart)
                    ends.add(i)
                    initialLetters.add(word.substring(0, 1))
                    fullLetters.add(word)
                }
            } else {
                i++
            }
        }
        if (initialLetters.isEmpty()) return null

        val matchedPositions = matchPositions(initialLetters, fullLetters, q, isDigitsQuery) ?: return null
        if (matchedPositions.isEmpty()) return null

        // Group matched positions into character ranges of the original name.
        val sorted = matchedPositions.distinct().sorted()
        val ranges = ArrayList<IntRange>()
        var index = 0
        while (index < sorted.size) {
            var next = index
            while (next + 1 < sorted.size && sorted[next + 1] == sorted[next] + 1) next++
            ranges.add(IntRange(starts[sorted[index]], ends[sorted[next]] - 1))
            index = next + 1
        }
        return ranges
    }

    /**
     * Tries initials first, then full syllables, to align [query] against the per-position letter targets.
     * Returns the list of matched position indices (greedy, earliest-first).
     */
    private fun matchPositions(
        initials: List<String>,
        full: List<String>,
        query: String,
        isDigitsQuery: Boolean,
    ): List<Int>? {
        fun tryMode(targets: List<String>): List<Int>? {
            val textBuilder = StringBuilder()
            val charToPosition = ArrayList<Int>()
            targets.forEachIndexed { position, target ->
                for (char in target) {
                    textBuilder.append(if (isDigitsQuery) digitForLetter(char) else char)
                    charToPosition.add(position)
                }
            }
            val text = textBuilder.toString()
            var queryIndex = 0
            val matched = ArrayList<Int>()
            for (i in text.indices) {
                if (queryIndex < query.length && text[i] == query[queryIndex]) {
                    matched.add(charToPosition[i])
                    queryIndex++
                    if (queryIndex == query.length) return matched
                }
            }
            return null
        }

        return tryMode(initials) ?: tryMode(full)
    }

    /** Digits typed on the dialpad / a pure-digit query: matches numbers + initials/full T9. */
    fun queryDigits(query: String, contacts: List<Contact>): List<ContactMatch> =
        query(query, contacts, isDigitsQuery = true)

    /** Pure letters query (contacts page): subsequence/prefix over initials & full pinyin. */
    fun queryLetters(query: String, contacts: List<Contact>): List<ContactMatch> =
        query(query, contacts, isDigitsQuery = false)

    private fun query(query: String, contacts: List<Contact>, isDigitsQuery: Boolean): List<ContactMatch> {
        val q = if (isDigitsQuery) query.filter { it.isDigit() } else query.trim().lowercase()
        if (q.isEmpty()) return emptyList()

        ensureBuilt(contacts)
        val recordsSnapshot = records
        val sourceSnapshot = snapshot

        val matches = ArrayList<ContactMatch>()
        for (contact in sourceSnapshot) {
            val record = recordsSnapshot[contact.id] ?: continue
            val score = bestScore(record, q, isDigitsQuery) ?: continue
            matches.add(ContactMatch(contact, score))
        }
        matches.sortWith(compareByDescending { it.score })
        return matches
    }

    private fun ensureBuilt(contacts: List<Contact>) {
        if (sourceList === contacts && records.isNotEmpty()) {
            return
        }
        rebuild(contacts)
    }

    // ---------------------------------------------------------------------------------
    // building
    // ---------------------------------------------------------------------------------

    private fun buildRecord(contact: Contact): PhoneticRecord? {
        val displayName = contact.getNameToDisplay().trim()
        if (displayName.isEmpty() || contact.phoneNumbers.isEmpty()) return null

        val parts = parseParts(contact, displayName) ?: return null
        val positions = parts.positions
        if (positions.isEmpty()) return null

        // Name-tier matching only uses the canonical (primary reading) sequence (R5 ruling).
        // The primary reading of a char is the one used to build the displayed abbreviation,
        // i.e. position.initials.first()/syllables.first(). A polyphonic char must NOT leak a
        // secondary reading into the name tier, otherwise 朝朝 (displayed CC -> "22") could be
        // matched through chao/zhao cross readings as "29". Alternate readings survive only in
        // highlightRanges() below (all-readings fallback).
        val canonicalInitials = buildString {
            for (position in positions) append(position.initials.first())
        }
        val canonicalFull = buildString {
            for (position in positions) append(position.syllables.first())
        }

        val abbr = if (parts.isChineseName) {
            buildString {
                for (position in positions) append(position.initials.first().uppercase())
            }
        } else {
            ""
        }

        val phoneDigits = contact.phoneNumbers
            .map { it.value.filter { digit -> digit.isDigit() } }
            .filter { it.isNotEmpty() }
            .distinct()

        return PhoneticRecord(
            contactId = contact.id,
            abbr = abbr,
            isChineseName = parts.isChineseName,
            familySyllableCount = parts.familySyllableCount,
            totalSyllableCount = positions.size,
            initialCandidates = listOfNotNull(canonicalInitials.asCandidate()),
            fullCandidates = listOfNotNull(canonicalFull.asCandidate()),
            phoneDigits = phoneDigits,
        )
    }

    private fun parseParts(contact: Contact, displayName: String): Parts? {
        val firstName = contact.firstName.trim()
        val middleName = contact.middleName.trim()
        val surname = contact.surname.trim()
        val firstMiddle = listOf(firstName, middleName).filter { it.isNotEmpty() }.joinToString(" ")
        val hasStructName = firstName.isNotEmpty() || middleName.isNotEmpty() || surname.isNotEmpty()

        if (!hasStructName) {
            return parseDisplayName(displayName)
        }

        val structChinese = PinyinConverter.containsChinese(firstMiddle) || PinyinConverter.containsChinese(surname)
        return if (structChinese) {
            parseChineseName(surname, firstMiddle, displayName)
        } else {
            parseLatinName(firstMiddle, surname)
        }
    }

    private fun parseDisplayName(displayName: String): Parts? {
        if (PinyinConverter.containsChinese(displayName)) {
            val (family, given) = splitFamilyAtStart(displayName)
            val positions = parsePositions(family) + parsePositions(given)
            return Parts(positions = positions, familySyllableCount = positionsOf(family), isChineseName = true)
        }

        val words = displayName.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null

        if (words.size == 1) {
            val positions = parsePositions(words.first())
            return Parts(positions, familySyllableCount = 0, isChineseName = false)
        }

        val givenPositions = parsePositions(words.dropLast(1).joinToString(" "))
        val familyPositions = parsePositions(words.last())
        return Parts(
            positions = givenPositions + familyPositions,
            familySyllableCount = familyPositions.size,
            isChineseName = false,
        )
    }

    private fun parseChineseName(surname: String, firstMiddle: String, displayName: String): Parts {
        var familyText = surname
        var givenText = firstMiddle

        when {
            familyText.isEmpty() && givenText.isNotEmpty() -> {
                val (family, given) = splitFamilyAtStart(givenText)
                familyText = family
                givenText = given
            }

            familyText.isNotEmpty() && givenText.isEmpty() -> {
                givenText = removeFamilyFromDisplay(displayName, familyText)
            }

            familyText.isEmpty() && givenText.isEmpty() -> {
                val (family, given) = splitFamilyAtStart(displayName)
                familyText = family
                givenText = given
            }
        }

        val familyPositions = parsePositions(familyText)
        val givenPositions = parsePositions(givenText)
        return Parts(
            positions = familyPositions + givenPositions,
            familySyllableCount = familyPositions.size,
            isChineseName = true,
        )
    }

    private fun parseLatinName(firstMiddle: String, surname: String): Parts {
        val givenPositions = parsePositions(firstMiddle)
        val familyPositions = parsePositions(surname)
        return Parts(
            positions = givenPositions + familyPositions,
            familySyllableCount = familyPositions.size,
            isChineseName = false,
        )
    }

    private fun positionsOf(text: String): Int = parsePositions(text).size

    /** Splits the leading Han run of [text] into family (1 char, or 2 if a compound surname) + the rest. */
    private fun splitFamilyAtStart(text: String): Pair<String, String> {
        val hanRun = text.takeWhile { PinyinConverter.isChineseChar(it) }
        if (hanRun.isEmpty()) return "" to text
        val familyLength = if (hanRun.length >= 2 && hanRun.take(2) in COMPOUND_SURNAMES) 2 else 1
        val family = hanRun.take(familyLength)
        val rest = hanRun.drop(familyLength) + text.drop(hanRun.length)
        return family to rest
    }

    private fun removeFamilyFromDisplay(displayName: String, family: String): String {
        var rest = displayName.trim()
        if (rest.startsWith(family)) {
            rest = rest.drop(family.length).trimStart(' ', ',', '，')
        } else if (rest.endsWith(family)) {
            rest = rest.dropLast(family.length).trimEnd()
        }
        return rest
    }

    /**
     * Converts [text] into ordered positions:
     *  - a Han char becomes one position whose initials/full syllables keep ALL its readings
     *    (the FIRST one is the primary reading used by buildRecord for the canonical name-tier
     *    candidate, matching the displayed abbreviation);
     *  - a Latin word becomes one position (letters ASCII-folded to 'a'..'z').
     */
    private fun parsePositions(text: String): List<Position> {
        val positions = ArrayList<Position>()
        var i = 0
        while (i < text.length) {
            val char = text[i]
            val readings = PinyinConverter.getReadings(char)
            if (readings.isNotEmpty()) {
                positions.add(
                    Position(
                        initials = readings.map { it.substring(0, 1) }.distinct(),
                        syllables = readings,
                    )
                )
                i++
            } else if (char.isLetter()) {
                val wordBuilder = StringBuilder()
                while (i < text.length) {
                    val current = text[i]
                    if (PinyinConverter.isChineseChar(current)) break
                    if (!current.isLetter()) break
                    wordBuilder.append(current)
                    i++
                }
                val word = normalizeLatinWord(wordBuilder.toString())
                if (word.isNotEmpty()) {
                    positions.add(
                        Position(
                            initials = listOf(word.first().toString()),
                            syllables = listOf(word),
                        )
                    )
                }
            } else {
                i++
            }
        }
        return positions
    }

    private fun normalizeLatinWord(rawWord: String): String {
        val decomposed = Normalizer.normalize(rawWord, Normalizer.Form.NFD)
        val normalized = StringBuilder()
        for (char in decomposed) {
            val lower = char.lowercaseChar()
            if (lower in 'a'..'z') {
                normalized.append(lower)
            }
        }
        return normalized.toString()
    }

    private fun String.asCandidate(): Candidate? {
        if (isEmpty()) return null
        if (any { it !in 'a'..'z' }) return null
        val digits = map { digitForLetter(it) }.joinToString("")
        return Candidate(letters = this, digits = digits)
    }

    private fun digitForLetter(char: Char): Char = when (char) {
        in 'a'..'c' -> '2'
        in 'd'..'f' -> '3'
        in 'g'..'i' -> '4'
        in 'j'..'l' -> '5'
        in 'm'..'o' -> '6'
        in 'p'..'s' -> '7'
        in 't'..'v' -> '8'
        in 'w'..'z' -> '9'
        else -> '0'
    }

    // ---------------------------------------------------------------------------------
    // scoring
    // ---------------------------------------------------------------------------------

    private fun bestScore(record: PhoneticRecord, query: String, isDigitsQuery: Boolean): Int? {
        var best: Int? = null

        fun consider(score: Int) {
            if (best == null || score > best!!) best = score
        }

        if (isDigitsQuery) {
            // Phone-number matches stay in their own low tier (below every name match).
            for (number in record.phoneDigits) {
                scoreNumber(number, query)?.let { consider(it) }
            }
        }

        val searchValue: (Candidate) -> String = if (isDigitsQuery) ({ it.digits }) else ({ it.letters })

        for (candidate in record.initialCandidates) {
            val value = searchValue(candidate)
            scoreInitials(value, query, record)?.let { consider(NAME_MATCH_BONUS + it) }
        }

        for (candidate in record.fullCandidates) {
            val value = searchValue(candidate)
            scoreFull(value, query)?.let { consider(NAME_MATCH_BONUS + it) }
        }

        return best
    }

    private fun scoreNumber(number: String, query: String): Int? = when {
        number == query -> 960
        number.startsWith(query) && query.length >= 5 -> 900
        number.contains(query) -> 450
        else -> null
    }

    private fun scoreInitials(candidate: String, query: String, record: PhoneticRecord): Int? {
        if (query.isEmpty()) return null
        return when {
            candidate == query -> 830

            candidate.startsWith(query) -> 800

            else -> {
                val substringIndex = candidate.indexOf(query)
                if (substringIndex >= 0) {
                    val base = 680
                    if (startsInPreferredZone(substringIndex, record)) base + 20 else base
                } else {
                    val startIndex = subsequenceStart(candidate, query) ?: return null
                    val base = 630
                    if (startsInPreferredZone(startIndex, record)) base + 20 else base
                }
            }
        }
    }

    private fun scoreFull(candidate: String, query: String): Int? {
        if (query.isEmpty()) return null
        return when {
            candidate == query -> 760

            candidate.startsWith(query) -> 740

            else -> {
                if (candidate.indexOf(query) >= 0) 620
                else if (subsequenceStart(candidate, query) != null) 560
                else null
            }
        }
    }

    /** Chinese: prefers matches starting inside the surname (front); Latin: inside the given name. */
    private fun startsInPreferredZone(startIndex: Int, record: PhoneticRecord): Boolean {
        val total = record.totalSyllableCount
        if (total == 0) return false
        val limit = if (record.isChineseName) {
            record.familySyllableCount
        } else {
            total - record.familySyllableCount
        }
        return startIndex < limit
    }

    /** Returns the earliest start index for which [query] is a (possibly gapped) subsequence of [text]. */
    private fun subsequenceStart(text: String, query: String): Int? {
        if (query.isEmpty()) return null
        var queryIndex = 0
        var startIndex = -1
        for (i in text.indices) {
            if (text[i] == query[queryIndex]) {
                if (queryIndex == 0) startIndex = i
                queryIndex++
                if (queryIndex == query.length) return startIndex
            }
        }
        return null
    }
}

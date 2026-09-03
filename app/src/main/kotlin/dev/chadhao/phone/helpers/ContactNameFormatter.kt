package dev.chadhao.phone.helpers

import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import com.goodwy.commons.models.contacts.Contact

/**
 * Paints [ranges] (character offsets inside [text]) with [color].
 * Out-of-range / malformed ranges are skipped instead of crashing a bind path.
 */
fun applyRangeHighlight(text: String, ranges: List<IntRange>, color: Int): CharSequence {
    val spannable = SpannableString(text)
    for (range in ranges) {
        val start = range.first.coerceAtLeast(0)
        val end = (range.last + 1).coerceAtMost(text.length)
        if (start >= end) continue
        try {
            spannable.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        } catch (_: IndexOutOfBoundsException) {
        }
    }
    return spannable
}

/**
 * Centralised display-name formatting for Chinese contacts.
 *
 * Requirement (real-device feedback): Chinese contacts must be shown as "姓+名"
 * with no inserted space and surname first (张三, 欧阳娜娜), everywhere in the app;
 * non-Chinese (Latin etc.) names must be kept exactly as they were before.
 *
 * Two entry points:
 *  - [format] formats a [Contact] using its structured given/family fields first;
 *  - [formatDisplayName] is the heuristic fallback used for plain name strings that
 *    were persisted before this change (recent-call cache, quick-dial slots, call-log
 *    CACHED_NAME), where no structured fields are available.
 *
 * The fallback only rewrites strings that are *pure Chinese* and contain CJK-word
 * separators (space / comma). It looks up which token is a surname (compound-surname
 * table + common single-character surnames) and reassembles it in surname-first order.
 * Strings that are single-token, already joined, contain Latin/numbers or localised
 * status words (未知/语音信箱/...) are left untouched.
 */
object ContactNameFormatter {

    /** Two-character (compound) surnames that never split across both Han chars. */
    val COMPOUND_SURNAMES = setOf(
        "欧阳", "司马", "上官", "诸葛", "司徒", "夏侯", "令狐", "皇甫",
        "公孙", "长孙", "慕容", "鲜于", "宇文", "尉迟", "申屠", "独孤",
        "轩辕", "南宫", "呼延", "东方", "百里", "东郭", "南门", "西门",
        "闻人", "淳于", "公羊", "澹台", "公冶", "宗政", "濮阳", "单于",
        "太叔", "仲孙", "钟离", "闾丘", "司空", "亓官", "司寇", "子车",
        "颛孙", "端木", "巫马", "公西", "漆雕", "乐正", "壤驷", "公良",
        "拓跋", "夹谷", "宰父", "谷梁", "段干", "梁丘", "左丘", "东门",
        "西门", "第五", "羊舌", "微生", "万俟", "赫连",
    )

    /** Common single-character Chinese surnames (百家姓-derived), used for fallback reordering. */
    private val SINGLE_SURNAMES = setOf(
        "赵", "钱", "孙", "李", "周", "吴", "郑", "王", "冯", "陈", "褚", "卫", "蒋", "沈", "韩", "杨",
        "朱", "秦", "尤", "许", "何", "吕", "施", "张", "孔", "曹", "严", "华", "金", "魏", "陶", "姜",
        "戚", "谢", "邹", "喻", "柏", "水", "窦", "章", "云", "苏", "潘", "葛", "奚", "范", "彭", "郎",
        "鲁", "韦", "昌", "马", "苗", "凤", "花", "方", "俞", "任", "袁", "柳", "酆", "鲍", "史", "唐",
        "费", "廉", "岑", "薛", "雷", "贺", "倪", "汤", "滕", "殷", "罗", "毕", "郝", "邬", "安", "常",
        "乐", "于", "时", "傅", "皮", "卞", "齐", "康", "伍", "余", "元", "卜", "顾", "孟", "平", "黄",
        "和", "穆", "萧", "尹", "姚", "邵", "湛", "汪", "祁", "毛", "禹", "狄", "米", "贝", "明", "臧",
        "计", "伏", "成", "戴", "谈", "宋", "茅", "庞", "熊", "纪", "舒", "屈", "项", "祝", "董", "梁",
        "杜", "阮", "蓝", "闵", "席", "季", "麻", "强", "贾", "路", "娄", "危", "江", "童", "颜", "郭",
        "梅", "盛", "林", "刁", "钟", "徐", "邱", "骆", "高", "夏", "蔡", "田", "樊", "胡", "凌", "霍",
        "虞", "万", "支", "柯", "昝", "管", "卢", "莫", "经", "房", "裘", "缪", "干", "解", "应", "宗",
        "丁", "宣", "贲", "邓", "郁", "单", "杭", "洪", "包", "诸", "左", "石", "崔", "吉", "钮", "龚",
        "程", "嵇", "邢", "滑", "裴", "陆", "荣", "翁", "荀", "羊", "於", "惠", "甄", "曲", "家", "封",
        "芮", "羿", "储", "靳", "汲", "邴", "糜", "松", "井", "段", "富", "巫", "乌", "焦", "巴", "弓",
        "牧", "隗", "山", "谷", "车", "侯", "宓", "蓬", "全", "郗", "班", "仰", "秋", "仲", "伊", "宫",
        "宁", "仇", "栾", "暴", "甘", "钭", "厉", "戎", "祖", "武", "符", "刘", "景", "詹", "束", "龙",
        "叶", "幸", "司", "韶", "郜", "黎", "蓟", "薄", "印", "宿", "白", "怀", "蒲", "邰", "从", "鄂",
        "索", "咸", "籍", "赖", "卓", "蔺", "屠", "蒙", "池", "乔", "阴", "郁", "胥", "能", "苍", "双",
        "闻", "莘", "党", "翟", "谭", "贡", "劳", "逄", "姬", "申", "扶", "堵", "冉", "宰", "郦", "雍",
        "却", "璩", "桑", "桂", "濮", "牛", "寿", "通", "边", "扈", "燕", "冀", "浦", "尚", "农", "温",
        "别", "庄", "晏", "柴", "瞿", "阎", "充", "慕", "连", "茹", "习", "宦", "艾", "鱼", "容", "向",
        "古", "易", "慎", "戈", "廖", "庾", "终", "暨", "居", "衡", "步", "都", "耿", "满", "弘", "匡",
        "国", "文", "寇", "广", "禄", "阙", "东", "欧", "殳", "沃", "利", "蔚", "越", "夔", "隆", "师",
        "巩", "厍", "聂", "晁", "勾", "敖", "融", "冷", "訾", "辛", "阚", "那", "简", "饶", "空", "曾",
        "毋", "沙", "乜", "养", "鞠", "须", "丰", "巢", "关", "蒯", "相", "查", "后", "荆", "红", "游",
        "竺", "权", "逯", "盖", "益", "桓", "公", "仉", "督", "岳", "帅", "缑", "亢", "况", "后", "有",
        "琴", "商", "牟", "佘", "佴", "伯", "赏", "墨", "哈", "谯", "笪", "年", "爱", "阳", "佟", "言",
        "福", "辜", "屠", "桑", "浦", "农", "车", "木", "母", "申", "司", "巫", "太", "左", "巩",
    )

    private val surnameTokens: Set<String> = COMPOUND_SURNAMES + SINGLE_SURNAMES

    /**
     * Formats a contact's display name.
     * Chinese names (any given/family token contains Han characters) are emitted as
     * "姓+名" with no space. All other names keep the original commons logic untouched.
     */
    fun format(contact: Contact): String {
        val raw = contact.getNameToDisplay().trim()
        if (raw.isEmpty()) return raw

        val prefix = contact.prefix.trim()
        val suffix = contact.suffix.trim()
        // Prefix/suffix or nickname-override edge cases: keep the commons output as-is.
        if (prefix.isNotEmpty() || suffix.isNotEmpty()) return raw

        val effectiveFirst =
            if (Contact.showNicknameInsteadNames && contact.nickname.isNotBlank()) contact.nickname.trim() else contact.firstName.trim()
        val middle = contact.middleName.trim()
        val surname = contact.surname.trim()

        val given = listOf(effectiveFirst, middle).filter { it.isNotEmpty() }
        if (given.isEmpty() && surname.isEmpty()) return raw // organisation / email / phone fallback

        val tokens = if (surname.isNotEmpty()) listOf(surname) + given else given
        if (tokens.isEmpty()) return raw
        // Only re-flow when every component is pure Han text. Mixed Latin/CJK names
        // (e.g. a Latin surname + Chinese given name) keep the commons output untouched.
        val allHan = tokens.all { it.all { ch -> PinyinConverter.isChineseChar(ch) } }
        if (!allHan) return raw

        // Remove whitespace between components, surname first.
        return tokens.joinToString("")
    }

    /**
     * Best-effort reformat of an already materialised name string (no structured fields).
     * Returns the input unchanged unless the whole string is Chinese and can be
     * confidently re-assembled surname-first without spaces.
     */
    fun formatDisplayName(name: String): String {
        if (name.isBlank()) return name

        val trimmed = name.trim()
        if (!PinyinConverter.containsChinese(trimmed)) return name

        val tokens = trimmed.split(Regex("[\\s,，、;；]+")).filter { it.isNotEmpty() }
        if (tokens.size <= 1) return trimmed

        // Only reorder when every token is pure Han text (avoids mangling "名 - 手机" style suffixes).
        val allHan = tokens.all { token -> token.all { PinyinConverter.isChineseChar(it) } }
        if (!allHan) return trimmed

        val surnameIndex = tokens.indexOfFirst { it in surnameTokens }
        val surnameCount = tokens.count { it in surnameTokens }
        if (surnameCount != 1) return tokens.joinToString("") // cannot disambiguate; keep original order, drop spaces

        val surname = tokens[surnameIndex]
        val rest = tokens.filterIndexed { index, _ -> index != surnameIndex }
        return (listOf(surname) + rest).joinToString("")
    }
}

package tw.kebbi.medreminder.scan

import tw.kebbi.medreminder.scan.UsageMethod

/** 從 OCR 全文擷取出的預填值；找不到的欄位為 null（不預填）。 */
data class OcrExtraction(
    val drugName: String?,
    val dosePerTime: Int?,
    val method: UsageMethod?,
    val frequency: FrequencyHint,
) {
    /** 本次沒找到的欄位用 [other] 補上（ML Kit 結果優先，Tesseract 補缺）。 */
    fun orElse(other: OcrExtraction) = OcrExtraction(
        drugName = drugName ?: other.drugName,
        dosePerTime = dosePerTime ?: other.dosePerTime,
        method = method ?: other.method,
        frequency = FrequencyHint(
            timesPerDay = frequency.timesPerDay ?: other.frequency.timesPerDay,
            slots = frequency.slots.ifEmpty { other.frequency.slots },
        ),
    )

    val isEmpty: Boolean get() = drugName == null && dosePerTime == null && method == null && frequency.isEmpty

    override fun toString(): String =
        "OcrExtraction(name=${drugName != null}, dose=$dosePerTime, method=$method, freq=$frequency)"
}

/**
 * 從藥袋 OCR 全文找藥名與用法（純 Kotlin）。
 * 以「藥品名稱」「用法用量」「每次」「每日」「飯後」「睡前」等關鍵字定位，找得到才預填。
 */
object OcrKeywordExtractor {

    private val NAME_LABELS = listOf("藥品名稱", "藥品名", "藥名", "品名")
    private val USAGE_LABELS = listOf("用法用量", "用法")
    /**
     * 用法行的定位關鍵字。「三餐」「早晚」單獨出現常是衛教文字（例如「三餐均衡飲食」），
     * 不拿來定位；真正的用法行通常也會有「飯後」「每日」等字。
     */
    private val USAGE_KEYWORDS = listOf("每次", "每日", "每天", "一天", "飯後", "飯前", "睡前", "小時")

    /** 藥名後面如果同一行接著其他欄位，要在這些字之前截斷。 */
    private val NAME_STOP_WORDS = listOf(
        "用法", "用量", "每次", "每日", "數量", "總量", "劑量", "適應症", "用途", "副作用", "注意", "外觀", "口服", "外用",
    )

    private val dosePattern = Regex("每次\\s*([0-9]{1,2}|[一二兩三四])\\s*(?:粒|顆|錠|包|片|膠囊|次|cc|CC|ml|mL|格|匙|滴)?")
    private val CHINESE_DIGITS = mapOf("一" to 1, "二" to 2, "兩" to 2, "三" to 3, "四" to 4)

    fun extract(rawText: String?): OcrExtraction {
        val text = normalize(rawText.orEmpty())
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return OcrExtraction(
            drugName = findDrugName(lines),
            dosePerTime = findDose(text),
            method = when {
                text.contains("口服") -> UsageMethod.ORAL
                text.contains("外用") -> UsageMethod.TOPICAL
                else -> null
            },
            frequency = FrequencyDictionary.match(usageText(lines), FrequencyDictionary.OCR_RULES),
        )
    }

    /** 全形轉半形（數字、英文、冒號、空白），方便比對。 */
    fun normalize(s: String): String = buildString(s.length) {
        for (c in s) {
            append(
                when (c) {
                    in '０'..'９' -> '0' + (c - '０')
                    in 'Ａ'..'Ｚ' -> 'A' + (c - 'Ａ')
                    in 'ａ'..'ｚ' -> 'a' + (c - 'ａ')
                    '：', '﹕' -> ':'
                    '　' -> ' '
                    else -> c
                }
            )
        }
    }

    private fun findDrugName(lines: List<String>): String? {
        for ((i, line) in lines.withIndex()) {
            val label = NAME_LABELS.firstOrNull { line.contains(it) } ?: continue
            val after = line.substringAfter(label).trimStart(':', ' ', '　', '-', '.')
            val candidate = cleanName(after) ?: lines.getOrNull(i + 1)?.let { cleanName(it) }
            if (candidate != null) return candidate
        }
        return null
    }

    private fun cleanName(s: String): String? {
        var name = s.trim()
        NAME_STOP_WORDS.forEach { stop ->
            val idx = name.indexOf(stop)
            if (idx >= 0) name = name.substring(0, idx)
        }
        name = name.trim().trimEnd(':', ',', '，', '、', ';', '；').trim()
        // 至少要有一個中文字或英文字母，且不要太長（避免抓到整段說明）
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) return null
        if (name.none { it.isLetter() }) return null
        if (NAME_LABELS.any { name == it }) return null
        return name
    }

    private fun findDose(text: String): Int? {
        val m = dosePattern.find(text) ?: return null
        val v = m.groupValues[1]
        return (v.toIntOrNull() ?: CHINESE_DIGITS[v])?.takeIf { it in 1..20 }
    }

    /** 只從用法相關的行比對頻率（用法標籤所在行與下一行、含用法關鍵字的行）。 */
    private fun usageText(lines: List<String>): String {
        val picked = linkedSetOf<Int>()
        lines.forEachIndexed { i, line ->
            if (USAGE_LABELS.any { line.contains(it) }) {
                picked += i
                if (i + 1 < lines.size) picked += i + 1
            }
            if (USAGE_KEYWORDS.any { line.contains(it) }) picked += i
        }
        return picked.sorted().joinToString("\n") { lines[it] }
    }

    private const val MAX_NAME_LENGTH = 40
}

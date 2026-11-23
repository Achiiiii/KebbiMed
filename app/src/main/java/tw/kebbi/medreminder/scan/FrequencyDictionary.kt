package tw.kebbi.medreminder.scan

import tw.kebbi.medreminder.scan.TimeSlot
import tw.kebbi.medreminder.scan.WizardOptions

/** 從用法文字推得的頻率提示；任一欄可為空（比對不到就不預填）。 */
data class FrequencyHint(
    val timesPerDay: Int? = null,
    val slots: Set<TimeSlot> = emptySet(),
) {
    val isEmpty: Boolean get() = timesPerDay == null && slots.isEmpty()

    /** 合併：每日次數先比對到的優先，時段取聯集。 */
    operator fun plus(other: FrequencyHint) = FrequencyHint(
        timesPerDay = timesPerDay ?: other.timesPerDay,
        slots = slots + other.slots,
    )
}

/** 一條頻率規則：Regex 比對到就產生提示；回傳 null 表示「比對到但不預填」（例如次數超出選項）。 */
class FrequencyRule(val name: String, val regex: Regex, val toHint: (MatchResult) -> FrequencyHint?)

/**
 * 可擴充的頻率字典（純 Kotlin）。
 * - [QR_RULES]：task.md 規定的 QR 格式 A 規則，只做這三條，不要猜。
 * - [OCR_RULES]：OCR 用，多了中文數字、常見處方縮寫與「三餐／早晚」等寫法。
 */
object FrequencyDictionary {

    /** 只接受精靈有的選項（1～4 次）。 */
    private fun times(n: Int?): Int? = n?.takeIf { it in WizardOptions.TIMES_PER_DAY }

    /** 每 N 小時一次 → 24/N 次；不整除或超出選項就不預填。 */
    private fun everyHours(hours: Int?): Int? {
        if (hours == null || hours <= 0 || 24 % hours != 0) return null
        return times(24 / hours)
    }

    private val CHINESE_DIGITS = mapOf('一' to 1, '二' to 2, '兩' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6)

    private fun parseNumber(s: String): Int? = s.toIntOrNull() ?: s.singleOrNull()?.let { CHINESE_DIGITS[it] }

    val QR_RULES: List<FrequencyRule> = listOf(
        FrequencyRule("每N小時使用1次", Regex("每(\\d+)小時使用1次")) { m ->
            everyHours(m.groupValues[1].toIntOrNull())?.let { FrequencyHint(timesPerDay = it) }
        },
        FrequencyRule("睡前使用", Regex("睡前使用")) {
            FrequencyHint(timesPerDay = 1, slots = setOf(TimeSlot.BEDTIME))
        },
        FrequencyRule("每日N次", Regex("每日(\\d)次")) { m ->
            times(m.groupValues[1].toIntOrNull())?.let { FrequencyHint(timesPerDay = it) }
        },
    )

    val OCR_RULES: List<FrequencyRule> = listOf(
        FrequencyRule("每N小時", Regex("每\\s*(\\d+)\\s*小時")) { m ->
            everyHours(m.groupValues[1].toIntOrNull())?.let { FrequencyHint(timesPerDay = it) }
        },
        FrequencyRule("每日/一天N次", Regex("(?:每日|每天|一日|一天)\\s*([1-6一二兩三四五六])\\s*次")) { m ->
            times(parseNumber(m.groupValues[1]))?.let { FrequencyHint(timesPerDay = it) }
        },
        FrequencyRule("三餐", Regex("三餐")) {
            FrequencyHint(timesPerDay = 3, slots = setOf(TimeSlot.MORNING, TimeSlot.NOON, TimeSlot.EVENING))
        },
        FrequencyRule("早晚", Regex("早晚")) {
            FrequencyHint(timesPerDay = 2, slots = setOf(TimeSlot.MORNING, TimeSlot.EVENING))
        },
        FrequencyRule("睡前", Regex("睡前")) { FrequencyHint(slots = setOf(TimeSlot.BEDTIME)) },
        // 常見處方縮寫（大小寫皆可、前後不能接英文字母）
        FrequencyRule("QID", Regex("(?i)(?<![A-Z])QID(?![A-Z])")) { FrequencyHint(timesPerDay = 4) },
        FrequencyRule("TID", Regex("(?i)(?<![A-Z])TID(?![A-Z])")) { FrequencyHint(timesPerDay = 3) },
        FrequencyRule("BID", Regex("(?i)(?<![A-Z])BID(?![A-Z])")) { FrequencyHint(timesPerDay = 2) },
        FrequencyRule("QD", Regex("(?i)(?<![A-Z])QD(?![A-Z])")) { FrequencyHint(timesPerDay = 1) },
        FrequencyRule("HS", Regex("(?i)(?<![A-Z])HS(?![A-Z])")) { FrequencyHint(slots = setOf(TimeSlot.BEDTIME)) },
        FrequencyRule("QnH", Regex("(?i)(?<![A-Z])Q(\\d{1,2})H(?![A-Z])")) { m ->
            everyHours(m.groupValues[1].toIntOrNull())?.let { FrequencyHint(timesPerDay = it) }
        },
    )

    /** 依序套用規則並合併結果；全部比對不到回傳空提示。 */
    fun match(text: String, rules: List<FrequencyRule>): FrequencyHint =
        rules.fold(FrequencyHint()) { acc, rule ->
            val m = rule.regex.find(text) ?: return@fold acc
            val hint = rule.toHint(m) ?: return@fold acc
            acc + hint
        }
}

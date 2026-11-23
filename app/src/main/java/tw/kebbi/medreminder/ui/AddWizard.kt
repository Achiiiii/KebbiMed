package tw.kebbi.medreminder.ui

import tw.kebbi.medreminder.api.MealRelation
import tw.kebbi.medreminder.api.Slot
import tw.kebbi.medreminder.api.dto.CatalogEntryDto
import tw.kebbi.medreminder.api.dto.IdentifiedBy
import tw.kebbi.medreminder.api.dto.PrescriptionDto
import tw.kebbi.medreminder.api.reference.ChangeRequestRules
import tw.kebbi.medreminder.api.reference.RoutineMinutes
import java.time.LocalDate

/** 加藥精靈的步驟（D38）。 */
enum class AddStep { TIMES, MEAL, DOSE, DAYS, CONFIRM }

/**
 * 加藥精靈（D38）：選好藥之後，一步一步問用法，最後確認再送申請。純邏輯，不依賴 android.*。
 *
 * - 每一步都有建議的答案，畫面上標出來，長者照著按就好：
 *   名稱對到目錄、而且有藥袋 QR 格式 A 時用 QR 的值（D26），沒有時用目錄預設。
 * - 「需要時才吃」（一天 0 次）不問飯前飯後、也不問吃幾天。
 * - 送出的是申請（D8），時段由後端照 [ChangeRequestRules.slotsFor] 排；確認頁用同一套規則先算給長者看。
 */
data class AddDraft(
    val entry: CatalogEntryDto,
    val identifiedBy: IdentifiedBy?,
    /** 藥袋 QR 格式 A 的用法（只有名稱對到這個藥時才有）。 */
    val qr: PrescriptionDto? = null,
    /** 藥袋 QR 格式 B 的總量（顆），用來算「吃幾天」的建議。 */
    val qrQuantity: Int? = null,
    val step: AddStep = AddStep.TIMES,
    val times: Int? = null,
    val meal: MealRelation? = null,
    val dose: Double? = null,
    val days: Int? = null,
    /** 「吃幾天」回答過了（`days` 是 null 時代表「一直吃」）。 */
    val daysAnswered: Boolean = false,
) {
    val fromScan: Boolean get() = identifiedBy != null && identifiedBy != IdentifiedBy.USER_SELECTED
    private val unit: String get() = entry.defaultDose.unit ?: "顆"
    private val asNeeded: Boolean get() = times == 0

    // ---------- 建議 ----------

    val suggestedTimes: Int
        get() = qr?.timesPerDay?.takeIf { it in 1..4 }
            ?: qr?.slots?.size?.takeIf { it in 1..4 }
            ?: entry.defaultSlots.count { it != Slot.CUSTOM }.coerceAtMost(4)

    fun suggestedMeal(times: Int): MealRelation {
        val relation = when (entry.mealRelation) {
            MealRelation.ANY -> when (entry.defaultSlots.firstOrNull()) {
                Slot.BEFORE_BREAKFAST, Slot.BEFORE_LUNCH, Slot.BEFORE_DINNER -> MealRelation.BEFORE
                else -> MealRelation.AFTER
            }
            else -> entry.mealRelation
        }
        return if (relation in mealOptions(times)) relation
        else if (relation == MealRelation.ON_WAKE) MealRelation.BEFORE // 起床空腹的藥一天好幾次：當成飯前
        else MealRelation.AFTER
    }

    val suggestedDose: Double get() = qr?.dosePerTime?.takeIf { it > 0 } ?: entry.defaultDose.amount?.takeIf { it > 0 } ?: 1.0

    /** 藥袋上的總量 ÷（每次幾顆 × 一天幾次），整除才建議。 */
    val suggestedDays: Int?
        get() {
            val q = qrQuantity ?: return null
            val perDay = (dose ?: suggestedDose) * (times ?: suggestedTimes)
            if (perDay <= 0) return null
            val d = q / perDay
            return d.toInt().takeIf { d == it.toDouble() && it in 1..ChangeRequestRules.MAX_DURATION_DAYS }
        }

    // ---------- 選項 ----------

    val timesOptions: List<Int> get() = listOf(1, 2, 3, 4, 0)

    fun mealOptions(times: Int): List<MealRelation> = if (times == 1) {
        listOfNotNull(
            MealRelation.BEFORE, MealRelation.AFTER, MealRelation.AT_BEDTIME,
            MealRelation.ON_WAKE.takeIf { entry.mealRelation == MealRelation.ON_WAKE },
        )
    } else listOf(MealRelation.BEFORE, MealRelation.AFTER)

    val doseOptions: List<Double> get() = (listOf(0.5, 1.0, 2.0, 3.0) + suggestedDose).distinct().sorted()

    /** null ＝ 一直吃（放最後）。 */
    val daysOptions: List<Int?> get() = (listOf(3, 7, 14, 28) + listOfNotNull(suggestedDays)).distinct().sorted() + listOf(null)

    /** 畫面上要標出來的答案：回答過就是回答的，沒有就是建議的。 */
    val markedTimes: Int get() = times ?: suggestedTimes
    val markedMeal: MealRelation get() = meal?.takeIf { it in mealOptions(markedTimes) } ?: suggestedMeal(markedTimes)
    val markedDose: Double get() = dose ?: suggestedDose
    /** 「吃幾天」沒有藥袋可以算時不建議（回傳 false 表示沒有要標的）。 */
    fun isMarkedDays(d: Int?): Boolean = if (daysAnswered) days == d else suggestedDays != null && suggestedDays == d

    /** 標出來的答案底下的小字：剛剛選的、藥袋上寫的、或建議。 */
    fun markHint(step: AddStep): String = when (step) {
        AddStep.TIMES -> if (times != null) "您選的" else if (qr?.timesPerDay != null || !qr?.slots.isNullOrEmpty()) "藥袋上寫的" else "建議"
        AddStep.MEAL -> if (meal != null) "您選的" else "建議"
        AddStep.DOSE -> if (dose != null) "您選的" else if (qr?.dosePerTime != null) "藥袋上寫的" else "建議"
        AddStep.DAYS -> if (daysAnswered) "您選的" else "藥袋上算的"
        AddStep.CONFIRM -> ""
    }

    // ---------- 回答 ----------

    fun answerTimes(n: Int): AddDraft = copy(
        times = n,
        meal = if (n == 0) null else meal?.takeIf { it in mealOptions(n) },
        step = if (n == 0) AddStep.DOSE else AddStep.MEAL,
    )

    fun answerMeal(m: MealRelation): AddDraft = copy(meal = m, step = AddStep.DOSE)

    fun answerDose(d: Double): AddDraft = copy(dose = d, step = if (asNeeded) AddStep.CONFIRM else AddStep.DAYS)

    fun answerDays(d: Int?): AddDraft = copy(days = d, daysAnswered = true, step = AddStep.CONFIRM)

    /** 上一步；已經在第一步時回傳 null（離開精靈）。 */
    fun back(): AddDraft? = when (step) {
        AddStep.TIMES -> null
        AddStep.MEAL -> copy(step = AddStep.TIMES)
        AddStep.DOSE -> copy(step = if (asNeeded) AddStep.TIMES else AddStep.MEAL)
        AddStep.DAYS -> copy(step = AddStep.DOSE)
        AddStep.CONFIRM -> copy(step = if (asNeeded) AddStep.DOSE else AddStep.DAYS)
    }

    /** 確認頁按「重新回答」：回到第一步，之前的答案留著當標記。 */
    fun restart(): AddDraft = copy(step = AddStep.TIMES)

    // ---------- 送出 ----------

    /** 送給後端的用法。QR 的時段只有在次數跟長者選的一樣時才帶。 */
    fun prescription(): PrescriptionDto {
        val n = times ?: suggestedTimes
        return PrescriptionDto(
            dosePerTime = dose ?: suggestedDose,
            timesPerDay = n,
            slots = qr?.slots?.takeIf { n > 0 && it.size == n } ?: emptyList(),
            mealRelation = if (n == 0) null else markedMeal,
            durationDays = if (n == 0) null else days,
            method = qr?.method,
        )
    }

    /** 後端會排的時段（確認頁先算給長者看）。 */
    fun slots(): List<Slot> = ChangeRequestRules.slotsFor(entry, prescription())

    // ---------- 文字 ----------

    fun timesLabel(n: Int): String = if (n == 0) "需要時才吃" else "一天 $n 次"
    fun mealLabel(m: MealRelation): String = when (m) {
        MealRelation.BEFORE -> "飯前"
        MealRelation.AFTER -> "飯後"
        MealRelation.AT_BEDTIME -> "睡前"
        MealRelation.ON_WAKE -> "起床空腹"
        MealRelation.ANY -> "都可以"
    }
    fun doseLabel(d: Double): String = ChangeRequestRules.doseText(d, unit)
    fun daysLabel(d: Int?): String = if (d == null) "一直吃" else "$d 天"

    /** 「每天早餐後 08:00、晚餐後 18:30」「每週一起床 07:00」。 */
    fun whenText(routine: RoutineMinutes): String {
        val s = slots()
        if (s.isEmpty()) return "需要時才吃，不會定時提醒"
        val prefix = entry.weeklyOn?.let { "每週${it.label.removePrefix("星期")}" } ?: "每天"
        return prefix + s.joinToString("、") { slot -> slot.label + (routine.timeOf(slot)?.let { " " + hm(it) } ?: "") }
    }

    /** 「吃 7 天，到 11 月 29 日」「一直吃，直到醫師說停」。 */
    fun daysText(today: LocalDate): String {
        val d = days ?: return "一直吃，直到醫師說停"
        val last = today.plusDays(d - 1L)
        return "吃 $d 天，到 ${last.monthValue} 月 ${last.dayOfMonth} 日"
    }

    /** 每一步要說的話（也是畫面標題）。 */
    fun question(today: LocalDate, routine: RoutineMinutes): String = when (step) {
        AddStep.TIMES -> (if (fromScan) "找到了，是${entry.displayName}。" else "${entry.displayName}。") + "一天吃幾次？請看藥袋上寫的。"
        AddStep.MEAL -> "飯前吃還是飯後吃？"
        AddStep.DOSE -> "每次吃幾${unit}？"
        AddStep.DAYS -> "這個藥要吃幾天？"
        AddStep.CONFIRM -> "請確認：${entry.displayName}，" + (if (asNeeded) "需要時才吃，不會定時提醒" else whenText(routine).replace(Regex(" \\d\\d:\\d\\d"), "")) +
            "，每次" + doseLabel(markedDose).let { if (it.first().isDigit()) " $it" else it } +
            (if (asNeeded) "" else "，${daysText(today)}") + "。這樣對嗎？"
    }

    companion object {
        fun start(entry: CatalogEntryDto, identifiedBy: IdentifiedBy?, qr: PrescriptionDto?, qrQuantity: Int?) =
            AddDraft(entry, identifiedBy, qr, qrQuantity)

        private fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
    }
}

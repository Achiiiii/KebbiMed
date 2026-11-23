package tw.kebbi.medreminder.api.reference

import tw.kebbi.medreminder.api.ErrorCodes
import tw.kebbi.medreminder.api.MealRelation
import tw.kebbi.medreminder.api.PrescriptionSlot
import tw.kebbi.medreminder.api.Slot
import tw.kebbi.medreminder.api.dto.CatalogEntryDto
import tw.kebbi.medreminder.api.dto.ChangeRequestDto
import tw.kebbi.medreminder.api.dto.ChangeRequestType
import tw.kebbi.medreminder.api.dto.PrescriptionDto
import java.time.LocalDate

/** 一位長者目前的處方和作息。 */
data class ElderPlan(
    val orders: List<OrderInput>,
    val routine: RoutineMinutes,
)

sealed class ApplyResult {
    /** 已套用：後端要發佈新版本（用 [PublishRules] 合併）。 */
    data class Applied(
        val plan: ElderPlan,
        val removedMedicationIds: Set<String> = emptySet(),
        val routineChanged: Boolean = false,
        val addedMedicationId: String? = null,
    ) : ApplyResult()

    /** 不用改。`reason` 例如 `ALREADY_ACTIVE`（這個藥已經在藥單裡）。 */
    data class NoChange(val reason: String) : ApplyResult()

    /** 只記錄（目錄裡沒有的藥，D26 (ii)）。 */
    data object Recorded : ApplyResult()

    /** 內容不合理，回 422。`detail` 會念給長者聽。 */
    data class Rejected(val code: String, val detail: String) : ApplyResult()
}

/**
 * 凱比送出的變更申請怎麼套用（D8：不需要核准，收到就套用）。參考實作。
 * 生效時間 ＝ 後端處理的時間；新舊 occurrence 的合併見 [PublishRules]。
 */
object ChangeRequestRules {

    /** 「吃幾天」最多幾天（D38）。 */
    const val MAX_DURATION_DAYS = 365

    /** [today]：後端處理申請那天（台北時間），「吃幾天」從這天算起。 */
    fun apply(
        plan: ElderPlan,
        req: ChangeRequestDto,
        catalog: Map<String, CatalogEntryDto>,
        today: LocalDate,
        newMedicationId: () -> String,
    ): ApplyResult = when (req.type) {
        ChangeRequestType.ADD_MEDICATION -> add(plan, req, catalog, today, newMedicationId)
        ChangeRequestType.REMOVE_MEDICATION -> {
            val id = req.medicationId
            when {
                id.isNullOrBlank() -> ApplyResult.Rejected(ErrorCodes.INVALID_REQUEST, "沒有指定要停哪一個藥。")
                plan.orders.none { it.medicationId == id } -> ApplyResult.NoChange("NOT_ACTIVE")
                else -> ApplyResult.Applied(
                    plan = plan.copy(orders = plan.orders.filter { it.medicationId != id }),
                    removedMedicationIds = setOf(id),
                )
            }
        }
        ChangeRequestType.CHANGE_ROUTINE -> {
            val dto = req.routine
            if (dto == null) {
                ApplyResult.Rejected(ErrorCodes.INVALID_REQUEST, "沒有指定新的作息時間。")
            } else {
                val r = runCatching { RoutineMinutes.from(dto) }.getOrNull()
                val problem = r?.validate() ?: if (r == null) "時間格式不對。" else null
                when {
                    problem != null -> ApplyResult.Rejected(ErrorCodes.INVALID_ROUTINE, problem)
                    r == plan.routine -> ApplyResult.NoChange("SAME_ROUTINE")
                    else -> ApplyResult.Applied(plan = plan.copy(routine = r!!), routineChanged = true)
                }
            }
        }
        ChangeRequestType.CLEAR_MEDICATIONS ->
            if (plan.orders.isEmpty()) ApplyResult.NoChange("ALREADY_EMPTY")
            else ApplyResult.Applied(
                plan = plan.copy(orders = emptyList()),
                removedMedicationIds = plan.orders.map { it.medicationId }.toSet(),
            )
        ChangeRequestType.UNLISTED_MEDICATION -> ApplyResult.Recorded
    }

    private fun add(
        plan: ElderPlan,
        req: ChangeRequestDto,
        catalog: Map<String, CatalogEntryDto>,
        today: LocalDate,
        newMedicationId: () -> String,
    ): ApplyResult {
        val entry = req.catalogId?.let { catalog[it] }
            ?: return ApplyResult.Rejected(ErrorCodes.UNKNOWN_CATALOG_ID, "找不到這個藥。")
        val p = req.prescription
        invalid(p)?.let { return ApplyResult.Rejected(ErrorCodes.INVALID_REQUEST, it) }
        // 已經吃完（過了 endDate）的同一個藥不算「已經有了」，直接換成新的
        val ended = plan.orders.filter { it.catalogId == entry.catalogId && it.endDate != null && it.endDate.isBefore(today) }
        if (plan.orders.any { it.catalogId == entry.catalogId && it !in ended }) return ApplyResult.NoChange("ALREADY_ACTIVE")
        val id = newMedicationId()
        val order = OrderInput(
            medicationId = id,
            catalogId = entry.catalogId,
            doseText = doseTextFor(entry, p),
            slots = slotsFor(entry, p),
            weeklyOn = entry.weeklyOn,
            endDate = p?.durationDays?.let { today.plusDays(it - 1L) },
            displayName = entry.displayName,
        )
        return ApplyResult.Applied(plan = plan.copy(orders = plan.orders - ended.toSet() + order), addedMedicationId = id)
    }

    /** 用法的數字不合理時回原因（會念給長者聽），合理回 null。 */
    private fun invalid(p: PrescriptionDto?): String? = when {
        p == null -> null
        p.timesPerDay != null && p.timesPerDay !in 0..4 -> "一天吃幾次要在 1 到 4 次之間。"
        p.dosePerTime != null && p.dosePerTime <= 0.0 -> "每次吃幾顆不對。"
        p.durationDays != null && p.durationDays !in 1..MAX_DURATION_DAYS -> "吃幾天要在 1 到 $MAX_DURATION_DAYS 天之間。"
        else -> null
    }

    /**
     * 加藥時的時段（D26 (b)、D38）。
     * - 沒有用法（`prescription` 是 null）：用目錄預設。
     * - 一天 0 次：需要時才吃，沒有時段。
     * - QR 有寫時段（早、午、晚、睡前）就照 QR。
     * - 否則依每日次數：1 次 → 見下面；2 次 → 早、晚；3 次 → 早、午、晚；4 次 → 早、午、晚、睡前。
     * - 早、午、晚要對到「飯前」還是「飯後」：長者有選（`mealRelation`）就照選的，沒有就照目錄。起床空腹的藥，「早」對到起床。
     * - 一天 1 次：選了睡前 → 睡前；起床空腹 → 起床；飯前、飯後 → 目錄預設時段的那一餐（目錄預設不是三餐時用早餐）。
     *   沒選飯前飯後時用目錄預設的第一個時段。
     */
    fun slotsFor(entry: CatalogEntryDto, p: PrescriptionDto?): List<Slot> {
        if (p == null) return entry.defaultSlots
        if (p.timesPerDay == 0) return emptyList()
        val relation = p.mealRelation ?: entry.mealRelation
        val ps: List<PrescriptionSlot> = when {
            p.slots.isNotEmpty() -> p.slots
            p.timesPerDay == 1 -> return listOf(onceADay(entry, p.mealRelation))
            p.timesPerDay == 2 -> listOf(PrescriptionSlot.MORNING, PrescriptionSlot.EVENING)
            p.timesPerDay == 3 -> listOf(PrescriptionSlot.MORNING, PrescriptionSlot.NOON, PrescriptionSlot.EVENING)
            p.timesPerDay == 4 -> PrescriptionSlot.entries
            else -> return entry.defaultSlots
        }
        return ps.distinct().map { slotOf(it, relation) }.sortedBy { it.ordinal }
    }

    private fun onceADay(entry: CatalogEntryDto, chosen: MealRelation?): Slot {
        val base = entry.defaultSlots.firstOrNull() ?: Slot.AFTER_BREAKFAST
        val meal = when (base) {
            Slot.BEFORE_LUNCH, Slot.AFTER_LUNCH -> PrescriptionSlot.NOON
            Slot.BEFORE_DINNER, Slot.AFTER_DINNER -> PrescriptionSlot.EVENING
            else -> PrescriptionSlot.MORNING
        }
        return when (chosen) {
            null, MealRelation.ANY -> base
            MealRelation.AT_BEDTIME -> Slot.BEDTIME
            MealRelation.ON_WAKE -> Slot.WAKE
            MealRelation.BEFORE, MealRelation.AFTER -> slotOf(meal, chosen)
        }
    }

    private fun slotOf(s: PrescriptionSlot, relation: MealRelation): Slot = when (s) {
        PrescriptionSlot.MORNING -> when (relation) {
            MealRelation.BEFORE -> Slot.BEFORE_BREAKFAST
            MealRelation.ON_WAKE -> Slot.WAKE
            else -> Slot.AFTER_BREAKFAST
        }
        PrescriptionSlot.NOON -> if (relation == MealRelation.BEFORE) Slot.BEFORE_LUNCH else Slot.AFTER_LUNCH
        PrescriptionSlot.EVENING -> if (relation == MealRelation.BEFORE) Slot.BEFORE_DINNER else Slot.AFTER_DINNER
        PrescriptionSlot.BEDTIME -> Slot.BEDTIME
    }

    /** 每次劑量：有寫就用寫的數字配目錄的單位，否則用目錄預設。 */
    fun doseTextFor(entry: CatalogEntryDto, p: PrescriptionDto?): String {
        val n = p?.dosePerTime?.takeIf { it > 0 } ?: return entry.defaultDose.text
        return doseText(n, entry.defaultDose.unit ?: "顆")
    }

    /** 0.5 →「半顆」、1 →「1 顆」、1.5 →「1 顆半」。 */
    fun doseText(n: Double, unit: String): String {
        val whole = n.toLong()
        val half = n - whole == 0.5
        return when {
            whole == 0L && half -> "半$unit"
            n == whole.toDouble() -> "$whole $unit"
            half -> "$whole ${unit}半"
            else -> "$n $unit"
        }
    }
}

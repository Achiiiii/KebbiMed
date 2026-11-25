package tw.kebbi.medreminder.api.reference

import tw.kebbi.medreminder.api.ApiConventions
import tw.kebbi.medreminder.api.Rules
import tw.kebbi.medreminder.api.Slot
import tw.kebbi.medreminder.api.Weekday
import tw.kebbi.medreminder.api.dto.OccurrenceDto
import tw.kebbi.medreminder.api.dto.OccurrenceItemDto
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 展開用的處方項目（後端資料庫的 MedicationOrder 轉成這個）。 */
data class OrderInput(
    val medicationId: String,
    val catalogId: String,
    val doseText: String,
    val slots: List<Slot>,
    /** `slots` 含 [Slot.CUSTOM] 時的指定時刻（一天中的第幾分鐘，D7）。 */
    val customTimes: List<Int> = emptyList(),
    val weeklyOn: Weekday? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    /** 顯示名稱（放進 occurrence 項目，給凱比顯示）。 */
    val displayName: String? = null,
) {
    fun activeOn(date: LocalDate): Boolean =
        (startDate == null || !date.isBefore(startDate)) &&
            (endDate == null || !date.isAfter(endDate)) &&
            (weeklyOn == null || weeklyOn.isoDayOfWeek == date.dayOfWeek.value)

    /** 這個藥一天中的所有時刻（分鐘）。 */
    fun timesOfDay(routine: RoutineMinutes): List<Pair<Slot, Int>> =
        slots.filter { it != Slot.CUSTOM }.mapNotNull { s -> routine.timeOf(s)?.let { s to it } } +
            (if (Slot.CUSTOM in slots) customTimes.map { Slot.CUSTOM to it } else emptyList())
}

/**
 * 把處方和作息展開成「服藥事件」（occurrence）。參考實作：假後端用它，第三方後端照這個規則實作。
 *
 * 規則：
 * - 一筆 occurrence ＝ 某天某個時刻要吃的一包藥。同一時刻的藥合併成一包。
 * - `occurrenceId` ＝ `occ_<yyyyMMdd>_<時段>_<長者>`，同一長者、同一天、同一時段固定不變。
 * - 晚吃期限（D10）：這包裡的每一種藥，到「下一次吃同一種藥」間隔的一半，取最早的；
 *   每週一次的藥給到當天 24:00；一天一次的藥是 12 小時後（可以跨日，CR-12）。至少是 T＋30。
 * - 生效前的時段要不要產生，交給 [PublishRules] 決定（D13）。
 */
object OccurrenceExpander {

    private val DAY: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

    fun occurrenceId(elderId: String, date: LocalDate, slot: Slot, minute: Int): String {
        val slotKey = if (slot == Slot.CUSTOM) "CUSTOM${ApiConventions.formatHm(minute).replace(":", "")}" else slot.name
        return "occ_${date.format(DAY)}_${slotKey}_$elderId"
    }

    fun expand(
        elderId: String,
        orders: List<OrderInput>,
        routine: RoutineMinutes,
        from: LocalDate,
        toInclusive: LocalDate,
        zone: ZoneId = ApiConventions.ZONE,
        /** 後台設定的 `reminderSettings.overdueAfterMinutes`：晚吃期限至少到 T＋這麼多分鐘。 */
        overdueAfterMin: Int = Rules.OVERDUE_AFTER_MIN,
    ): List<OccurrenceDto> {
        val out = mutableListOf<OccurrenceDto>()
        var date = from
        while (!date.isAfter(toInclusive)) {
            out += expandDay(elderId, orders, routine, date, zone, overdueAfterMin)
            date = date.plusDays(1)
        }
        return out.sortedBy { it.scheduledAt.let(ApiConventions::parseTime) }
    }

    private fun expandDay(
        elderId: String,
        orders: List<OrderInput>,
        routine: RoutineMinutes,
        date: LocalDate,
        zone: ZoneId,
        overdueAfterMin: Int,
    ): List<OccurrenceDto> {
        // 時刻（分鐘）→ 這個時刻的 (時段, 處方)
        val buckets = sortedMapOf<Int, MutableList<Pair<Slot, OrderInput>>>()
        for (o in orders.filter { it.activeOn(date) }) {
            for ((slot, minute) in o.timesOfDay(routine)) buckets.getOrPut(minute) { mutableListOf() } += slot to o
        }
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        return buckets.map { (minute, entries) ->
            // 同一時刻有多個時段時，用固定時段裡最早的那個當這包的時段
            val slot = entries.map { it.first }.minByOrNull { if (it == Slot.CUSTOM) Int.MAX_VALUE else it.ordinal }!!
            val ordersHere = entries.map { it.second }.distinctBy { it.medicationId }
            val scheduledAt = dayStart + minute * 60_000L
            val cutoffMin = ordersHere.minOf { lateCutoffMinute(it, minute, routine) }
            val lateDeadline = maxOf(dayStart + (cutoffMin * 60_000.0).toLong(), scheduledAt + overdueAfterMin * 60_000L)
            OccurrenceDto(
                occurrenceId = occurrenceId(elderId, date, slot, minute),
                date = ApiConventions.formatDate(date),
                slot = slot,
                slotLabel = slot.label,
                scheduledAt = ApiConventions.formatTime(scheduledAt, zone),
                lateDeadline = ApiConventions.formatTime(lateDeadline, zone),
                items = ordersHere.map { OccurrenceItemDto(it.medicationId, it.doseText, it.catalogId, it.displayName) },
            )
        }
    }

    /**
     * 晚吃期限（一天中的第幾分鐘，可能超過 1440 表示隔天）。
     * 到下一次吃同一種藥間隔的一半；每週一次的藥給到當天結束。
     */
    fun lateCutoffMinute(order: OrderInput, minute: Int, routine: RoutineMinutes): Double {
        if (order.weeklyOn != null) return 1440.0
        val times = order.timesOfDay(routine).map { it.second }.distinct().sorted()
        if (times.isEmpty()) return 1440.0
        val next = times.firstOrNull { it > minute } ?: (times.first() + 1440)
        return minute + (next - minute) / 2.0
    }
}

package tw.kebbi.medreminder.engine

import tw.kebbi.medreminder.data.AppData
import tw.kebbi.medreminder.data.Clock
import tw.kebbi.medreminder.data.DoseStatus
import tw.kebbi.medreminder.data.Med
import tw.kebbi.medreminder.data.MedDb
import tw.kebbi.medreminder.data.Slot
import tw.kebbi.medreminder.data.SlotState

/** 提醒規則的參數。要調整等候時間，改這裡就好。 */
object Rules {
    const val REMIND_AGAIN_MIN = 15  // 提醒後幾分鐘沒按「吃了」→ 再提醒一次
    const val MISS_AFTER_MIN = 30    // 時間到後幾分鐘還沒吃 → 逾時未服，通知照服員（還可以晚吃）
    // 正式「漏服」：逾時未服，而且過了晚吃期限（到下一次吃同一種藥間隔的一半），見 lateCutoffMin()
    const val SNOOZE_MIN = 10        // 按「等一下」後幾分鐘再提醒
    const val IDLE_STANDBY_SEC = 20  // 首頁類畫面沒有操作幾秒 → 待機
    const val AUTO_BACK_SEC = 12     // 獎勵、稍後、漏吃等畫面幾秒後回待機
}

/** 引擎判斷出的事件：畫面要跳轉、要同步到後台。 */
sealed class EngineEvent {
    data class Fire(val slot: Slot, val again: Boolean = false) : EngineEvent()
    data class Repeat(val slot: Slot) : EngineEvent()
    /** 逾時未服：時間到 30 分鐘還沒吃（還可以晚吃）。 */
    data class Missed(val slot: Slot, val wasReminding: Boolean) : EngineEvent()
    /** 正式漏服：逾時未服，又過了晚吃期限（或換日前都沒吃）。 */
    data class Lapsed(val slot: Slot) : EngineEvent()
    data class Rollover(val newDay: String, val yesterdayAllDone: Boolean) : EngineEvent()
}

/**
 * 提醒引擎：純邏輯，不碰畫面和 Android。
 * 畫面每秒呼叫一次 tick()；鬧鐘響時也呼叫一次。兩邊結果一致。
 */
object ReminderEngine {

    fun medsIn(d: AppData, slot: Slot, ms: Long = Clock.nowMs()): List<Med> {
        val dow = Clock.dayOfWeek(ms)
        return d.meds.mapNotNull { MedDb.get(it) }
            .filter { slot in it.slots && (it.weeklyOn == null || it.weeklyOn == dow) }
    }

    /** 這個時段要吃的藥名，例：「利尿劑、降血壓藥」。 */
    fun medNames(d: AppData, slot: Slot, ms: Long = Clock.nowMs()): String =
        medsIn(d, slot, ms).map { it.name }.distinct().joinToString("、")

    /** 給長者看的「哪一包」：早餐後 08:00 的利尿劑、降血壓藥（沒有藥名時：早餐後 08:00 的藥）。 */
    fun doseText(d: AppData, slot: Slot, withTime: Boolean = false, ms: Long = Clock.nowMs()): String {
        val names = medNames(d, slot, ms).ifEmpty { "藥" }
        val time = if (withTime) " ${Clock.fmt(d.schedule.timeOf(slot))} " else ""
        return "${slot.label}${time}的$names"
    }

    /** 今天有藥的時段（依時間排序）。同一時段多種藥算一次。 */
    fun activeSlots(d: AppData, ms: Long = Clock.nowMs()): List<Slot> =
        Slot.entries.filter { medsIn(d, it, ms).isNotEmpty() }.sortedBy { d.schedule.timeOf(it) }

    /** 算進今天次數的時段（不含「明天開始」）。 */
    fun counted(d: AppData) = activeSlots(d).filter { (d.slots[it]?.status ?: DoseStatus.PENDING) != DoseStatus.SKIP }
    fun takenCount(d: AppData) = counted(d).count { d.slots[it]?.status == DoseStatus.TAKEN }
    fun allDone(d: AppData) = counted(d).let { c -> c.isNotEmpty() && c.all { d.slots[it]?.status == DoseStatus.TAKEN } }
    fun nextSlot(d: AppData): Slot? = activeSlots(d).firstOrNull {
        d.slots[it]?.status in listOf(DoseStatus.PENDING, DoseStatus.REMINDING, DoseStatus.SNOOZED)
    }

    /** 換日：前一天全部吃完，花 +1；今天的狀態重設。開機時已超過漏吃時間的時段直接標成漏吃。 */
    fun ensureDay(d: AppData, ms: Long = Clock.nowMs()): Pair<AppData, EngineEvent?> {
        val key = Clock.dayKey(ms)
        if (d.day == key) return d to null
        val yesterdayDone = d.day != null && allDone(d)
        val nm = Clock.minuteOfDay(ms)
        val base = d.copy(day = key, slots = emptyMap(), flowers = d.flowers + if (yesterdayDone) 1 else 0)
        val slots = activeSlots(base, ms).associateWith { s ->
            val t = base.schedule.timeOf(s)
            if (nm >= t + Rules.MISS_AFTER_MIN) SlotState(DoseStatus.MISSED, note = "開機前已過") else SlotState()
        }
        val ev = if (d.day != null) EngineEvent.Rollover(key, yesterdayDone) else null
        return base.copy(slots = slots) to ev
    }

    /** 藥單或作息改變後呼叫：新出現且今天已過的時段 → 明天開始；不再需要的時段移除。 */
    fun syncSlots(d: AppData, ms: Long = Clock.nowMs()): AppData {
        val nm = Clock.minuteOfDay(ms)
        val act = activeSlots(d, ms)
        val slots = d.slots.filterKeys { it in act }.toMutableMap()
        for (s in act) if (s !in slots) {
            slots[s] = if (nm >= d.schedule.timeOf(s)) SlotState(DoseStatus.SKIP, note = "今天已過，明天開始") else SlotState()
        }
        return d.copy(slots = slots)
    }

    /**
     * 依現在時間更新狀態，回傳新狀態和這次發生的事件（一次最多一個提醒事件）。
     */
    fun tick(d0: AppData, ms: Long = Clock.nowMs()): Pair<AppData, List<EngineEvent>> {
        val events = mutableListOf<EngineEvent>()
        // 換日前還是「逾時未服」的時段 → 正式漏服
        if (d0.setupDone && d0.day != null && d0.day != Clock.dayKey(ms))
            d0.slots.filter { (_, st) -> st.status == DoseStatus.MISSED && !st.lapsed }.keys.forEach { events += EngineEvent.Lapsed(it) }
        val (d1, roll) = ensureDay(d0, ms)
        roll?.let { events += it }
        if (!d1.setupDone) return d1 to events
        val nm = Clock.minuteOfDay(ms)
        val slots = d1.slots.toMutableMap()
        for (s in activeSlots(d1, ms)) {
            val st = slots[s] ?: continue
            val t = d1.schedule.timeOf(s)
            val inWindow = nm >= t && nm < t + Rules.MISS_AFTER_MIN
            when {
                st.status == DoseStatus.PENDING && inWindow -> {
                    slots[s] = st.copy(status = DoseStatus.REMINDING, remindedAt = Clock.fmt(nm.toInt()))
                    events += EngineEvent.Fire(s); break
                }
                (st.status == DoseStatus.REMINDING || st.status == DoseStatus.SNOOZED) && !st.secondReminder &&
                    nm >= t + Rules.REMIND_AGAIN_MIN && inWindow -> {
                    slots[s] = st.copy(status = DoseStatus.REMINDING, secondReminder = true, snoozeUntil = null)
                    events += EngineEvent.Repeat(s); break
                }
                st.status == DoseStatus.SNOOZED && st.snoozeUntil != null && nm >= st.snoozeUntil && inWindow -> {
                    slots[s] = st.copy(status = DoseStatus.REMINDING, snoozeUntil = null)
                    events += EngineEvent.Fire(s, again = true); break
                }
                st.status in listOf(DoseStatus.PENDING, DoseStatus.REMINDING, DoseStatus.SNOOZED) &&
                    nm >= t + Rules.MISS_AFTER_MIN -> {
                    slots[s] = st.copy(status = DoseStatus.MISSED, note = "超過 ${Rules.MISS_AFTER_MIN} 分鐘沒吃")
                    events += EngineEvent.Missed(s, st.status != DoseStatus.PENDING); break
                }
                st.status == DoseStatus.MISSED && !st.lapsed && nm > lateCutoffMin(d1, s, ms) -> {
                    slots[s] = st.copy(lapsed = true, note = "過了晚吃期限")
                    events += EngineEvent.Lapsed(s)
                }
            }
        }
        return d1.copy(slots = slots) to events
    }

    /** 按「吃了」。逾時未服之後才吃的，記成「晚吃」。 */
    fun take(d: AppData, s: Slot, ms: Long = Clock.nowMs()): AppData {
        val st = d.slots[s] ?: SlotState()
        val note = if (st.status == DoseStatus.MISSED) "晚吃" else st.note
        return d.copy(slots = d.slots + (s to st.copy(status = DoseStatus.TAKEN, takenAt = Clock.hms(ms), note = note, lapsed = false)))
    }

    /**
     * 晚吃期限（一天中的第幾分鐘，可能超過 1440 表示隔天）：
     * 這個時段每一種藥，到「下一次吃同一種藥」間隔的一半，取最早的。每週一次的藥不限，給到當天結束。
     */
    fun lateCutoffMin(d: AppData, s: Slot, ms: Long = Clock.nowMs()): Double {
        val t = d.schedule.timeOf(s)
        var cutoff = Double.MAX_VALUE
        for (m in medsIn(d, s, ms)) {
            if (m.weeklyOn != null) continue
            val times = m.slots.map { d.schedule.timeOf(it) }.sorted()
            val next = times.firstOrNull { it > t } ?: (times.first() + 1440)
            cutoff = minOf(cutoff, t + (next - t) / 2.0)
        }
        return if (cutoff == Double.MAX_VALUE) 1440.0 else cutoff
    }

    /**
     * 漏吃的藥能不能補吃？
     * 一般用藥原則：想起來就補吃；但如果已經接近下一次吃同一種藥的時間（過了一半間隔），
     * 這次就不要補，下次照常吃，不可以一次吃兩份。
     * 回傳 null 表示可以補吃；否則回傳「不要補吃」的原因（給凱比說）。
     */
    fun lateTakeBlockReason(d: AppData, s: Slot, ms: Long = Clock.nowMs()): String? {
        val t = d.schedule.timeOf(s)
        val nm = Clock.minuteOfDay(ms)
        val elapsed = nm - t
        for (m in medsIn(d, s, ms)) {
            val times = m.slots.map { d.schedule.timeOf(it) }.sorted()
            val next = times.firstOrNull { it > t } ?: (times.first() + 1440)
            val gap = (next - t).toDouble()
            if (m.weeklyOn == null && elapsed > gap / 2) {
                val nextLabel = m.slots.firstOrNull { d.schedule.timeOf(it) == next % 1440 }?.label ?: "下一次"
                return "已經快到下一次吃${m.name}的時間（$nextLabel ${Clock.fmt(next)}）了。這次就不要吃了，下次照常吃就好，不要一次吃兩份喔。"
            }
        }
        return null
    }

    /** 晚吃：逾時未服之後吃了，標成已吃，備註「晚吃」。 */
    fun lateTake(d: AppData, s: Slot, ms: Long = Clock.nowMs()): AppData =
        d.copy(slots = d.slots + (s to (d.slots[s] ?: SlotState()).copy(status = DoseStatus.TAKEN, takenAt = Clock.hms(ms), note = "晚吃", lapsed = false)))

    fun snooze(d: AppData, s: Slot, ms: Long = Clock.nowMs()): AppData {
        val until = Clock.minuteOfDay(ms).toInt() + Rules.SNOOZE_MIN
        return d.copy(slots = d.slots + (s to (d.slots[s] ?: SlotState()).copy(status = DoseStatus.SNOOZED, snoozeUntil = until)))
    }

    /** 下一次需要叫醒 App 的時間（給鬧鐘用）。 */
    fun nextWakeMs(d: AppData, ms: Long = Clock.nowMs()): Long {
        val candidates = mutableListOf<Long>()
        candidates += Clock.startOfDay(ms) + 24 * 3600_000L + 5_000L // 換日
        if (d.setupDone) for (s in activeSlots(d, ms)) {
            val st = d.slots[s] ?: continue
            val t = d.schedule.timeOf(s)
            val mins = when (st.status) {
                DoseStatus.PENDING -> listOf(t, t + Rules.MISS_AFTER_MIN)
                DoseStatus.REMINDING -> listOf(if (st.secondReminder) t + Rules.MISS_AFTER_MIN else t + Rules.REMIND_AGAIN_MIN, t + Rules.MISS_AFTER_MIN)
                DoseStatus.SNOOZED -> listOfNotNull(st.snoozeUntil, t + Rules.REMIND_AGAIN_MIN, t + Rules.MISS_AFTER_MIN)
                DoseStatus.MISSED -> if (st.lapsed) emptyList() else listOf(kotlin.math.floor(lateCutoffMin(d, s, ms)).toInt() + 1)
                else -> emptyList()
            }
            mins.map { Clock.todayAt(it, ms) }.filter { it > ms + 500 }.minOrNull()?.let { candidates += it }
        }
        return candidates.min()
    }
}

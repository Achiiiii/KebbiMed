package tw.kebbi.medreminder.engine

import tw.kebbi.medreminder.api.Rules
import tw.kebbi.medreminder.data.OccStatus
import tw.kebbi.medreminder.data.Occurrence
import kotlin.math.ceil

/** 只有畫面用的常數（和後端無關的才放這裡；共用的在 [Rules]）。 */
object UiRules {
    /** 首頁類畫面沒有操作幾秒 → 待機。 */
    const val IDLE_STANDBY_SEC = 20
    /** 獎勵、稍後、結果等畫面幾秒後回待機。 */
    const val AUTO_BACK_SEC = 12
}

/** 引擎判斷出的狀態轉換。每一個都會變成一筆服藥事件送給後端。 */
sealed class EngineEvent {
    abstract val occ: Occurrence

    /** 第一次提醒（T）。 */
    data class Fire(override val occ: Occurrence) : EngineEvent()
    /** 再提醒（T＋15，或「等一下」到期，D11）。 */
    data class RemindAgain(override val occ: Occurrence) : EngineEvent()
    /** 逾時未服（T＋後台設定的分鐘數，預設 30）。 */
    data class Overdue(override val occ: Occurrence, val wasReminded: Boolean) : EngineEvent()
    /** 漏服（過了晚吃期限）。 */
    data class Missed(override val occ: Occurrence) : EngineEvent()
    /** 凱比關機期間錯過，開機時已經過了晚吃期限（D14）。 */
    data class NotReminded(override val occ: Occurrence) : EngineEvent()
}

/** 按「吃了」的結果（第 2.6 節的表：以按下時的狀態判斷）。 */
enum class TakeOutcome { TAKEN, LATE_TAKEN, ALREADY_TAKEN, TOO_EARLY, TOO_LATE, NOT_FOUND }

/**
 * 提醒引擎：純邏輯，不碰畫面和 Android。固定的常數在 `:api` 的 `Rules`；
 * 後台可以改的（等一下幾分鐘、最多幾次、幾分鐘後逾時）在每一包的 [Occurrence.settings]。
 * 鬧鐘響、畫面每秒更新、通知按鈕都呼叫同一套函式，結果一致。
 */
object ReminderEngine {
    private const val MIN = 60_000L

    /** 逾時時間 ＝ T＋後台設定的分鐘數。後端應該保證晚吃期限不早於它；萬一更早，以晚吃期限為準，狀態才不會錯亂。 */
    fun overdueAt(o: Occurrence): Long = minOf(o.scheduledAt + o.settings.overdueAfterMinutes * MIN, o.lateDeadline)
    private fun remindAgainAt(o: Occurrence) = o.scheduledAt + Rules.REMIND_AGAIN_MIN * MIN

    /** 一次狀態轉換；沒有要轉換時回傳 null。 */
    fun step(o: Occurrence, now: Long): Pair<Occurrence, EngineEvent>? = when (o.status) {
        OccStatus.PENDING -> when {
            now >= o.lateDeadline -> o.copy(status = OccStatus.NOT_REMINDED).let { it to EngineEvent.NotReminded(it) }
            now >= overdueAt(o) -> o.copy(status = OccStatus.OVERDUE).let { it to EngineEvent.Overdue(it, wasReminded = false) }
            now >= o.scheduledAt -> o.copy(status = OccStatus.REMINDING, remindedAt = now).let { it to EngineEvent.Fire(it) }
            else -> null
        }
        OccStatus.REMINDING -> when {
            now >= overdueAt(o) -> o.copy(status = OccStatus.OVERDUE).let { it to EngineEvent.Overdue(it, wasReminded = true) }
            !o.secondReminder && now >= remindAgainAt(o) -> o.copy(secondReminder = true).let { it to EngineEvent.RemindAgain(it) }
            else -> null
        }
        // D11：「等一下」期間不觸發 T＋15；到期時就算再提醒；到期剛好是 T＋30 時直接逾時
        OccStatus.SNOOZED -> when {
            now >= overdueAt(o) -> o.copy(status = OccStatus.OVERDUE, snoozeUntil = null).let { it to EngineEvent.Overdue(it, wasReminded = true) }
            now >= (o.snoozeUntil ?: 0L) -> o.copy(status = OccStatus.REMINDING, secondReminder = true, snoozeUntil = null)
                .let { it to EngineEvent.RemindAgain(it) }
            else -> null
        }
        OccStatus.OVERDUE -> if (now >= o.lateDeadline) o.copy(status = OccStatus.MISSED).let { it to EngineEvent.Missed(it) } else null
        else -> null
    }

    /** 一次把所有到期的轉換都處理完（CR-01）。 */
    fun tick(occs: List<Occurrence>, now: Long): Pair<List<Occurrence>, List<EngineEvent>> {
        val events = mutableListOf<EngineEvent>()
        val out = occs.map { o0 ->
            var o = o0
            repeat(6) {
                val r = step(o, now) ?: return@map o
                o = r.first
                events += r.second
            }
            o
        }
        return out to events.sortedBy { it.occ.scheduledAt }
    }

    // ---------- 長者的操作 ----------

    fun takeOutcome(o: Occurrence?, now: Long): TakeOutcome = when {
        o == null -> TakeOutcome.NOT_FOUND
        o.status == OccStatus.TAKEN -> TakeOutcome.ALREADY_TAKEN
        o.status == OccStatus.MISSED || o.status == OccStatus.NOT_REMINDED || now >= o.lateDeadline -> TakeOutcome.TOO_LATE
        now < o.scheduledAt - Rules.EARLY_TAKE_MIN * MIN -> TakeOutcome.TOO_EARLY
        o.status == OccStatus.OVERDUE || now >= overdueAt(o) -> TakeOutcome.LATE_TAKEN
        else -> TakeOutcome.TAKEN
    }

    /** 清單上能不能直接按這包（D15：T−30 起，到晚吃期限為止）。 */
    fun canTake(o: Occurrence, now: Long): Boolean = takeOutcome(o, now).let { it == TakeOutcome.TAKEN || it == TakeOutcome.LATE_TAKEN }

    fun take(o: Occurrence, now: Long): Occurrence = o.copy(
        status = OccStatus.TAKEN, takenAt = now, late = takeOutcome(o, now) == TakeOutcome.LATE_TAKEN, snoozeUntil = null,
    )

    /** 按過的次數到了後台設定的上限、或剩不到 2 分鐘就逾時時，不顯示「等一下」（D11）。 */
    fun canSnooze(o: Occurrence, now: Long): Boolean =
        o.status == OccStatus.REMINDING && o.snoozeCount < o.settings.maxSnoozes &&
            overdueAt(o) - now >= Rules.SNOOZE_HIDE_BELOW_MIN * MIN

    /** 稍後到期 ＝ min(按下時間＋後台設定的分鐘數, 逾時時間)（D11）。 */
    fun snoozeUntil(o: Occurrence, now: Long): Long = minOf(now + o.settings.snoozeMinutes * MIN, overdueAt(o))

    fun snooze(o: Occurrence, now: Long): Occurrence =
        o.copy(status = OccStatus.SNOOZED, snoozeUntil = snoozeUntil(o, now), snoozeCount = o.snoozeCount + 1)

    /** 「好，N 分鐘後再提醒您」的 N（實際分鐘數）。 */
    fun snoozeMinutes(o: Occurrence, now: Long): Int = ceil((snoozeUntil(o, now) - now) / MIN.toDouble()).toInt().coerceAtLeast(1)

    // ---------- 查詢 ----------

    /** 下一次要叫醒 App 的時間（下一個轉換點）。沒有就是 null。 */
    fun nextWakeMs(occs: List<Occurrence>, now: Long): Long? = occs.flatMap { o ->
        when (o.status) {
            OccStatus.PENDING -> listOf(o.scheduledAt, overdueAt(o), o.lateDeadline)
            OccStatus.REMINDING -> listOfNotNull(if (o.secondReminder) null else remindAgainAt(o), overdueAt(o))
            OccStatus.SNOOZED -> listOfNotNull(o.snoozeUntil, overdueAt(o))
            OccStatus.OVERDUE -> listOf(o.lateDeadline)
            else -> emptyList()
        }
    }.filter { it > now }.minOrNull()

    /** 正在提醒中的（最早的那包）。畫面由這個決定（CR-02）。 */
    fun activeReminder(occs: List<Occurrence>): Occurrence? =
        occs.filter { it.status == OccStatus.REMINDING }.minByOrNull { it.scheduledAt }

    /** 逾時未服、長者還沒看過的（最早的那包）。 */
    fun unseenOverdue(occs: List<Occurrence>): Occurrence? =
        occs.filter { it.status == OccStatus.OVERDUE && !it.overdueSeen }.minByOrNull { it.scheduledAt }

    /** 逾時未服、還可以晚吃的。 */
    fun overdueTakeable(occs: List<Occurrence>, now: Long): List<Occurrence> =
        occs.filter { it.status == OccStatus.OVERDUE && now < it.lateDeadline }.sortedBy { it.scheduledAt }

    fun forDate(occs: List<Occurrence>, date: String): List<Occurrence> =
        occs.filter { it.date == date }.sortedBy { it.scheduledAt }

    /** 下一包還沒吃的（含提醒中、稍後）。 */
    fun nextOpen(occs: List<Occurrence>, now: Long): Occurrence? =
        occs.filter { it.status == OccStatus.PENDING || it.status == OccStatus.REMINDING || it.status == OccStatus.SNOOZED }
            .filter { it.lateDeadline > now }
            .minByOrNull { it.scheduledAt }

    fun takenCount(day: List<Occurrence>) = day.count { it.status == OccStatus.TAKEN }
    fun allDone(day: List<Occurrence>) = day.isNotEmpty() && day.all { it.status == OccStatus.TAKEN }
}

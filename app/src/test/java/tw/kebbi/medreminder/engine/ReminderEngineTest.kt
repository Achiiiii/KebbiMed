package tw.kebbi.medreminder.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kebbi.medreminder.api.ApiConventions
import tw.kebbi.medreminder.api.Slot
import tw.kebbi.medreminder.api.reference.OccurrenceExpander
import tw.kebbi.medreminder.api.reference.OrderInput
import tw.kebbi.medreminder.api.reference.RoutineMinutes
import tw.kebbi.medreminder.data.OccItem
import tw.kebbi.medreminder.data.OccStatus
import tw.kebbi.medreminder.data.Occurrence
import java.time.LocalDate

/**
 * 提醒規則的情境測試。
 * 用「情境」的寫法：給藥單和作息、在某個時間做某件事、預期看到什麼。
 * 中途設定藥單、每週一次的藥在 `:api` 的 PublishRulesTest、OccurrenceExpanderTest；逾時通知照服員、凱比離線在 DoseStatusRulesTest。
 * 鬧鐘從冷啟動叫起 App、被凱比臉蓋住、點橫幅打開 App、提醒中離開前景，要在凱比上測。
 */
class ReminderEngineTest {

    private val day = LocalDate.of(2026, 11, 23) // 星期一
    private val routine = RoutineMinutes.DEFAULT  // 早餐 08:00、午餐 12:30、晚餐 18:30、睡覺 22:00

    private fun t(hm: String, plusDays: Long = 0) =
        ApiConventions.parseTime("${day.plusDays(plusDays)}T$hm:00+08:00")

    /** 一個情境：展開藥單 → 依時間跑引擎 → 記下事件。 */
    private inner class Scenario(vararg orders: OrderInput, days: Long = 1) {
        var occs: List<Occurrence> = OccurrenceExpander.expand("e1", orders.toList(), routine, day, day.plusDays(days)).map { o ->
            Occurrence(o.occurrenceId, o.date, o.slot, o.slotLabel, ApiConventions.parseTime(o.scheduledAt), ApiConventions.parseTime(o.lateDeadline),
                o.items.map { OccItem(it.medicationId, names[it.medicationId] ?: "藥", it.doseText) })
        }
        val events = mutableListOf<Pair<Long, EngineEvent>>()

        fun at(ms: Long): List<EngineEvent> {
            val (n, ev) = ReminderEngine.tick(occs, ms)
            occs = n
            events += ev.map { ms to it }
            return ev
        }

        fun get(slot: Slot, plusDays: Long = 0) = occs.first { it.slot == slot && it.date == day.plusDays(plusDays).toString() }

        fun update(o: Occurrence) { occs = occs.map { if (it.id == o.id) o else it } }

        fun take(slot: Slot, ms: Long, plusDays: Long = 0): TakeOutcome {
            val o = get(slot, plusDays)
            val r = ReminderEngine.takeOutcome(o, ms)
            if (r == TakeOutcome.TAKEN || r == TakeOutcome.LATE_TAKEN) update(ReminderEngine.take(o, ms))
            return r
        }

        fun snooze(slot: Slot, ms: Long): Long? {
            val o = get(slot)
            if (!ReminderEngine.canSnooze(o, ms)) return null
            update(ReminderEngine.snooze(o, ms))
            return get(slot).snoozeUntil
        }
    }

    private val names = mapOf("m01" to "降血壓藥", "m02" to "降血壓藥", "m13" to "利尿劑", "m28" to "胃藥", "m41" to "甲狀腺藥", "m05" to "降血脂藥")
    private fun med(id: String, vararg slots: Slot) = OrderInput(id, id.uppercase(), "1 顆", slots.toList())

    private val bp = med("m01", Slot.AFTER_BREAKFAST)                       // 08:00，晚吃期限 20:00
    private val diuretic = med("m13", Slot.AFTER_BREAKFAST, Slot.AFTER_DINNER) // 08:00、18:30

    @Test
    fun s01_takeOnTime() {
        val s = Scenario(bp)
        assertTrue(s.at(t("08:00")).single() is EngineEvent.Fire)
        assertEquals(TakeOutcome.TAKEN, s.take(Slot.AFTER_BREAKFAST, t("08:01")))
        assertFalse(s.get(Slot.AFTER_BREAKFAST).late)
        assertTrue(s.at(t("08:15")).isEmpty())
    }

    @Test
    fun s02_snoozeRemindsAfterTenMinutes() {
        val s = Scenario(bp)
        s.at(t("08:00"))
        assertEquals(t("08:12"), s.snooze(Slot.AFTER_BREAKFAST, t("08:02")))
        assertTrue(s.at(t("08:11")).isEmpty())
        assertTrue(s.at(t("08:12")).single() is EngineEvent.RemindAgain)
    }

    @Test
    fun s03_snoozeIsNotInterruptedByT15() {
        // D11：稍後提醒期間，不觸發 T＋15 的再提醒
        val s = Scenario(bp)
        s.at(t("08:00"))
        s.snooze(Slot.AFTER_BREAKFAST, t("08:12"))
        assertTrue(s.at(t("08:15")).isEmpty())
        assertTrue(s.at(t("08:22")).single() is EngineEvent.RemindAgain)
    }

    @Test
    fun s04_snoozeIsCappedAtOverdue() {
        // D11：稍後到期 = min(按下時間+10, T+30)；說實際分鐘數；到期剛好是 T+30 時直接進入逾時
        val s = Scenario(bp)
        s.at(t("08:00"))
        val o = s.get(Slot.AFTER_BREAKFAST)
        assertEquals(5, ReminderEngine.snoozeMinutes(o, t("08:25")))
        assertEquals(t("08:30"), s.snooze(Slot.AFTER_BREAKFAST, t("08:25")))
        val ev = s.at(t("08:30"))
        assertTrue(ev.single() is EngineEvent.Overdue)
        assertEquals(OccStatus.OVERDUE, s.get(Slot.AFTER_BREAKFAST).status)
    }

    @Test
    fun s04b_noSnoozeWhenLessThanTwoMinutesLeft() {
        val s = Scenario(bp)
        s.at(t("08:00"))
        assertTrue(ReminderEngine.canSnooze(s.get(Slot.AFTER_BREAKFAST), t("08:27")))
        assertFalse(ReminderEngine.canSnooze(s.get(Slot.AFTER_BREAKFAST), t("08:29")))
    }

    @Test
    fun s05_secondReminderAtT15() {
        val s = Scenario(bp)
        s.at(t("08:00"))
        val ev = s.at(t("08:15"))
        assertTrue(ev.single() is EngineEvent.RemindAgain)
        assertTrue(s.get(Slot.AFTER_BREAKFAST).secondReminder)
        assertTrue(s.at(t("08:20")).isEmpty())
    }

    @Test
    fun s06_overdueAndNextReminderAtSameTimeAreBothHandled() {
        // CR-01：起床 07:00 的逾時點 07:30 就是早餐前的提醒時間（背景只靠鬧鐘）
        val s = Scenario(med("m41", Slot.WAKE), med("m28", Slot.BEFORE_BREAKFAST), bp)
        s.at(t("07:00"))
        val ev = s.at(t("07:30"))
        assertEquals(2, ev.size)
        assertEquals(OccStatus.OVERDUE, s.get(Slot.WAKE).status)
        assertEquals(OccStatus.REMINDING, s.get(Slot.BEFORE_BREAKFAST).status)
        // 鬧鐘接著排到早餐前的再提醒（07:45），不會跳過
        assertEquals(t("07:45"), ReminderEngine.nextWakeMs(s.occs, t("07:30")))
    }

    @Test
    fun s07_sameTimeIsOneReminder() {
        val s = Scenario(bp, diuretic)
        val ev = s.at(t("08:00"))
        assertEquals(1, ev.size)
        assertEquals(2, (ev.single() as EngineEvent.Fire).occ.items.size)
    }

    @Test
    fun s08_coldStartShowsActiveReminderFromState() {
        // CR-02：畫面由狀態決定。App 冷啟動時（T+5），引擎一跑就有「提醒中」的那包
        val s = Scenario(bp)
        s.at(t("08:05"))
        assertNotNull(ReminderEngine.activeReminder(s.occs))
        assertEquals(s.get(Slot.AFTER_BREAKFAST).id, ReminderEngine.activeReminder(s.occs)!!.id)
    }

    @Test
    fun s10_takeAfterOverdueIsLate() {
        val s = Scenario(bp)
        s.at(t("08:00")); s.at(t("08:31"))
        assertEquals(OccStatus.OVERDUE, s.get(Slot.AFTER_BREAKFAST).status)
        assertEquals(TakeOutcome.LATE_TAKEN, s.take(Slot.AFTER_BREAKFAST, t("10:00")))
        assertTrue(s.get(Slot.AFTER_BREAKFAST).late)
    }

    @Test
    fun s11_afterDeadlineDoNotTake() {
        // 一天一次的降血壓藥 08:00，晚吃期限是 12 小時後的 20:00
        val s = Scenario(bp)
        s.at(t("08:00")); s.at(t("08:31"))
        assertEquals(TakeOutcome.TOO_LATE, ReminderEngine.takeOutcome(s.get(Slot.AFTER_BREAKFAST), t("20:00")))
        assertTrue(s.at(t("20:00")).single() is EngineEvent.Missed)
        assertEquals(OccStatus.MISSED, s.get(Slot.AFTER_BREAKFAST).status)
    }

    @Test
    fun s12_bedtimeAcrossMidnightIsNotCleared() {
        // CR-11：睡前 22:00 沒吃，過午夜不會被清掉；22:30 逾時，隔天 10:00 才漏服
        val s = Scenario(med("m05", Slot.BEDTIME))
        s.at(t("22:00")); s.at(t("22:30"))
        assertEquals(OccStatus.OVERDUE, s.get(Slot.BEDTIME).status)
        assertTrue(s.at(t("00:01", 1)).isEmpty())
        assertEquals(OccStatus.OVERDUE, s.get(Slot.BEDTIME).status)
        assertEquals(TakeOutcome.LATE_TAKEN, ReminderEngine.takeOutcome(s.get(Slot.BEDTIME), t("09:00", 1)))
        assertTrue(s.at(t("10:00", 1)).any { it is EngineEvent.Missed })
    }

    @Test
    fun s14_rebootProcessesEverythingAtOnce() {
        // 重開機時 3 包都已經過了 T+30：一次全部處理；過了晚吃期限的記「關機沒提醒到」（D14）
        val s = Scenario(med("m41", Slot.WAKE), med("m28", Slot.BEFORE_BREAKFAST), bp)
        val ev = s.at(t("11:00"))
        assertEquals(3, ev.size)
        // 都是一天一次的藥，晚吃期限是 12 小時後，所以 11:00 時三包都還可以晚吃
        listOf(Slot.WAKE, Slot.BEFORE_BREAKFAST, Slot.AFTER_BREAKFAST).forEach { assertEquals(OccStatus.OVERDUE, s.get(it).status) }
        assertTrue(ev.all { it is EngineEvent.Overdue && !it.wasReminded })
    }

    @Test
    fun s14b_offWholeDayMarksNotReminded() {
        val s = Scenario(bp)
        val ev = s.at(t("21:00"))
        assertTrue(ev.single() is EngineEvent.NotReminded)
        assertEquals(OccStatus.NOT_REMINDED, s.get(Slot.AFTER_BREAKFAST).status)
    }

    @Test
    fun s16_takeUpTo30MinutesEarly() {
        // D15：T−30 起可以按「吃了」，到了 T 就不再提醒
        val s = Scenario(bp)
        assertEquals(TakeOutcome.TOO_EARLY, ReminderEngine.takeOutcome(s.get(Slot.AFTER_BREAKFAST), t("07:29")))
        assertEquals(TakeOutcome.TAKEN, s.take(Slot.AFTER_BREAKFAST, t("07:40")))
        assertTrue(s.at(t("08:00")).isEmpty())
        assertTrue(s.at(t("08:30")).isEmpty())
    }

    @Test
    fun s17_takeWhileSnoozed() {
        val s = Scenario(bp)
        s.at(t("08:00"))
        s.snooze(Slot.AFTER_BREAKFAST, t("08:02"))
        assertEquals(TakeOutcome.TAKEN, s.take(Slot.AFTER_BREAKFAST, t("08:05")))
        assertTrue(s.at(t("08:12")).isEmpty())
    }

    @Test
    fun s18_twoMedsOfSameKindAreBothNamed() {
        // CR-08：兩種降血壓藥不能只念一次
        val s = Scenario(bp, med("m02", Slot.AFTER_BREAKFAST))
        assertEquals("降血壓藥兩種", s.get(Slot.AFTER_BREAKFAST).medNames)
    }

    @Test
    fun notificationButtonTable() {
        // 第 2.6 節：按橫幅「吃了」時，依按下時的狀態判斷
        val s = Scenario(bp)
        s.at(t("08:00"))
        val o = s.get(Slot.AFTER_BREAKFAST)
        assertEquals(TakeOutcome.TAKEN, ReminderEngine.takeOutcome(o, t("08:10")))
        assertEquals(TakeOutcome.LATE_TAKEN, ReminderEngine.takeOutcome(o, t("08:40")))
        assertEquals(TakeOutcome.TOO_LATE, ReminderEngine.takeOutcome(o, t("20:00")))
        assertEquals(TakeOutcome.ALREADY_TAKEN, ReminderEngine.takeOutcome(ReminderEngine.take(o, t("08:10")), t("08:11")))
        assertEquals(TakeOutcome.NOT_FOUND, ReminderEngine.takeOutcome(null, t("08:11")))
    }

    @Test
    fun nextWakeIsTheNextTransition() {
        val s = Scenario(bp)
        assertEquals(t("08:00"), ReminderEngine.nextWakeMs(s.occs, t("07:00")))
        s.at(t("08:00"))
        assertEquals(t("08:15"), ReminderEngine.nextWakeMs(s.occs, t("08:00")))
        s.at(t("08:15"))
        assertEquals(t("08:30"), ReminderEngine.nextWakeMs(s.occs, t("08:15")))
        s.at(t("08:30"))
        assertEquals(t("20:00"), ReminderEngine.nextWakeMs(s.occs, t("08:30")))
        s.take(Slot.AFTER_BREAKFAST, t("09:00"))
        assertNull(ReminderEngine.nextWakeMs(s.occs.filter { it.date == day.toString() }, t("09:00")))
    }

    @Test
    fun overdueSeenIsNotShownAgain() {
        val s = Scenario(bp)
        s.at(t("08:31"))
        assertNotNull(ReminderEngine.unseenOverdue(s.occs))
        s.update(s.get(Slot.AFTER_BREAKFAST).copy(overdueSeen = true))
        assertNull(ReminderEngine.unseenOverdue(s.occs))
        assertEquals(1, ReminderEngine.overdueTakeable(s.occs, t("09:00")).size)
    }

    @Test
    fun remindingTooLongAgoStillGoesOverdueThenMissed() {
        val s = Scenario(diuretic)
        s.at(t("08:00"))
        val ev = s.at(t("13:20")) // 早上這包晚吃期限 13:15
        assertTrue(ev.any { it is EngineEvent.Overdue } && ev.any { it is EngineEvent.Missed })
        assertEquals(OccStatus.MISSED, s.get(Slot.AFTER_BREAKFAST).status)
    }
}

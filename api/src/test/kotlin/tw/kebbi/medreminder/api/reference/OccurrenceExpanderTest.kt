package tw.kebbi.medreminder.api.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kebbi.medreminder.api.ApiConventions
import tw.kebbi.medreminder.api.Slot
import tw.kebbi.medreminder.api.Weekday
import java.time.LocalDate

class OccurrenceExpanderTest {

    private val routine = RoutineMinutes.DEFAULT // 早餐 08:00、午餐 12:30、晚餐 18:30、睡覺 22:00
    private val day = LocalDate.of(2026, 11, 23) // 星期一

    private fun order(id: String, vararg slots: Slot, weekly: Weekday? = null) =
        OrderInput(id, "C$id", "1 顆", slots.toList(), weeklyOn = weekly)

    @Test
    fun diureticMorningDeadlineIsHalfwayToEvening() {
        // 例：利尿劑 08:00、18:30 吃，早上那包最晚 13:15 吃
        val occ = OccurrenceExpander.expand("e1", listOf(order("m13", Slot.AFTER_BREAKFAST, Slot.AFTER_DINNER)), routine, day, day)
        assertEquals(2, occ.size)
        assertEquals("2026-11-23T08:00:00+08:00", occ[0].scheduledAt)
        assertEquals("2026-11-23T13:15:00+08:00", occ[0].lateDeadline)
        assertEquals("2026-11-24T01:15:00+08:00", occ[1].lateDeadline)
    }

    @Test
    fun onceDailyBedtimeDeadlineCrossesMidnight() {
        // CR-12：睡前 22:00 一天一次的藥，期限是隔天 10:00
        val occ = OccurrenceExpander.expand("e1", listOf(order("m05", Slot.BEDTIME)), routine, day, day).single()
        assertEquals("2026-11-23T22:00:00+08:00", occ.scheduledAt)
        assertEquals("2026-11-24T10:00:00+08:00", occ.lateDeadline)
    }

    @Test
    fun weeklyMedOnlyOnItsDayUntilMidnight() {
        val o = order("m39", Slot.WAKE, weekly = Weekday.MON)
        val occ = OccurrenceExpander.expand("e1", listOf(o), routine, day, day.plusDays(6))
        assertEquals(1, occ.size)
        assertEquals("2026-11-23T07:00:00+08:00", occ[0].scheduledAt)
        assertEquals("2026-11-24T00:00:00+08:00", occ[0].lateDeadline)
    }

    @Test
    fun sameTimeIsMergedIntoOnePack() {
        val occ = OccurrenceExpander.expand(
            "e1", listOf(order("m01", Slot.AFTER_BREAKFAST), order("m13", Slot.AFTER_BREAKFAST, Slot.AFTER_DINNER)),
            routine, day, day,
        )
        val morning = occ.first()
        assertEquals(2, morning.items.size)
        // 一天一次的降血壓藥是 20:00，利尿劑是 13:15，取最早的
        assertEquals("2026-11-23T13:15:00+08:00", morning.lateDeadline)
        assertEquals("occ_20261123_AFTER_BREAKFAST_e1", morning.occurrenceId)
    }

    @Test
    fun customTimeSlot() {
        val o = OrderInput("m99", "C99", "1 顆", listOf(Slot.CUSTOM), customTimes = listOf(15 * 60))
        val occ = OccurrenceExpander.expand("e1", listOf(o), routine, day, day).single()
        assertEquals(Slot.CUSTOM, occ.slot)
        assertEquals("occ_20261123_CUSTOM1500_e1", occ.occurrenceId)
    }

    @Test
    fun deadlineIsAtLeastOverdueTime() {
        // 同一種藥在早餐前、早餐後各吃一次（間隔 30 分），期限至少要到 T＋30
        val o = order("mx", Slot.BEFORE_BREAKFAST, Slot.AFTER_BREAKFAST)
        val first = OccurrenceExpander.expand("e1", listOf(o), routine, day, day).first()
        val at = ApiConventions.parseTime(first.scheduledAt)
        assertTrue(ApiConventions.parseTime(first.lateDeadline) >= at + 30 * 60_000L)
    }
}

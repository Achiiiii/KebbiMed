package tw.kebbi.medreminder.api.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kebbi.medreminder.api.ApiConventions
import tw.kebbi.medreminder.api.Slot
import java.time.LocalDate

class PublishRulesTest {

    private val day = LocalDate.of(2026, 11, 23)
    private val routine = RoutineMinutes.DEFAULT
    private fun t(hm: String) = ApiConventions.parseTime("2026-11-23T$hm:00+08:00")
    private fun expand(orders: List<OrderInput>, r: RoutineMinutes = routine) =
        OccurrenceExpander.expand("e1", orders, r, day, day.plusDays(1))

    private val m13 = OrderInput("m13", "M13", "1 顆", listOf(Slot.AFTER_BREAKFAST, Slot.AFTER_DINNER))
    private val m05 = OrderInput("m05", "M05", "1 顆", listOf(Slot.BEDTIME))

    @Test
    fun firstPublishSkipsTimesAlreadyPassed() {
        // D13：首次啟用當天下午才設定，早上的時段不算
        val now = t("14:00")
        val out = PublishRules.compose(emptyList(), expand(listOf(m13)), effectiveFrom = now, now = now)
        assertTrue(out.none { it.occurrenceId == "occ_20261123_AFTER_BREAKFAST_e1" })
        assertTrue(out.any { it.occurrenceId == "occ_20261123_AFTER_DINNER_e1" })
    }

    @Test
    fun addingMedKeepsStartedOccurrencesAndChangesFutureOnes() {
        val prev = PublishRules.compose(emptyList(), expand(listOf(m13)), t("07:00"), t("07:00"))
        val now = t("09:00")
        val out = PublishRules.compose(prev, expand(listOf(m13, m05)), now, now)
        val morning = out.first { it.occurrenceId == "occ_20261123_AFTER_BREAKFAST_e1" }
        assertEquals(1, morning.items.size) // 已經開始，不受新藥影響
        assertTrue(out.any { it.occurrenceId == "occ_20261123_BEDTIME_e1" })
    }

    @Test
    fun removingMedTakesItOutOfThePackInProgress() {
        val both = OrderInput("m01", "M01", "1 顆", listOf(Slot.AFTER_BREAKFAST))
        val prev = PublishRules.compose(emptyList(), expand(listOf(m13, both)), t("07:00"), t("07:00"))
        val now = t("08:05") // 早餐後這包正在提醒中
        val out = PublishRules.compose(prev, expand(listOf(both)), now, now, removedMedicationIds = setOf("m13"))
        val morning = out.first { it.occurrenceId == "occ_20261123_AFTER_BREAKFAST_e1" }
        assertEquals(listOf("m01"), morning.items.map { it.medicationId })
        assertFalse(out.any { it.occurrenceId == "occ_20261123_AFTER_DINNER_e1" })
    }

    @Test
    fun removingOnlyMedDeletesThePackInProgress() {
        val prev = PublishRules.compose(emptyList(), expand(listOf(m13)), t("07:00"), t("07:00"))
        val now = t("08:05")
        val out = PublishRules.compose(prev, emptyList(), now, now, removedMedicationIds = setOf("m13"))
        assertTrue(out.none { it.date == "2026-11-23" && ApiConventions.parseTime(it.scheduledAt) >= t("08:00") })
    }

    @Test
    fun takenPackIsNotTouchedWhenMedRemoved() {
        val prev = PublishRules.compose(emptyList(), expand(listOf(m13)), t("07:00"), t("07:00"))
        val now = t("08:05")
        val out = PublishRules.compose(
            prev, emptyList(), now, now,
            removedMedicationIds = setOf("m13"), takenOccurrenceIds = setOf("occ_20261123_AFTER_BREAKFAST_e1"),
        )
        assertTrue(out.any { it.occurrenceId == "occ_20261123_AFTER_BREAKFAST_e1" })
    }

    @Test
    fun routineChangeMovesPassedTimeToNow() {
        // 原本晚餐 18:30；改成 17:00，現在 17:30：今天晚餐後那包改成現在提醒
        val prev = PublishRules.compose(emptyList(), expand(listOf(m13)), t("07:00"), t("07:00"))
        val now = t("17:30")
        val newRoutine = routine.copy(dinner = 17 * 60)
        val out = PublishRules.compose(prev, expand(listOf(m13), newRoutine), now, now, routineChanged = true)
        val evening = out.first { it.occurrenceId == "occ_20261123_AFTER_DINNER_e1" }
        assertEquals(now, ApiConventions.parseTime(evening.scheduledAt))
        assertTrue(ApiConventions.parseTime(evening.lateDeadline) > now + 30 * 60_000L)
    }

    @Test
    fun tomorrowEffectiveKeepsTodayAsIs() {
        val prev = PublishRules.compose(emptyList(), expand(listOf(m13)), t("07:00"), t("07:00"))
        val tomorrow = ApiConventions.parseTime("2026-11-24T00:00:00+08:00")
        val out = PublishRules.compose(prev, expand(listOf(m05)), tomorrow, t("09:00"))
        assertTrue(out.any { it.occurrenceId == "occ_20261123_AFTER_DINNER_e1" })
        assertFalse(out.any { it.occurrenceId == "occ_20261123_BEDTIME_e1" })
        assertTrue(out.any { it.occurrenceId == "occ_20261124_BEDTIME_e1" })
    }
}

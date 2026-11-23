package tw.kebbi.medreminder.api.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kebbi.medreminder.api.dto.DoseEventType

/** 屬於後端的情境：逾時通知照服員、凱比離線。 */
class DoseStatusRulesTest {

    private val min = 60_000L
    private val t = 1_000_000_000_000L
    private val deadline = t + 300 * min

    private fun status(events: List<EventInput>, now: Long, online: Boolean = true) =
        DoseStatusRules.status(t, deadline, events, now, online)

    @Test
    fun earlyTakeCountsAsOnTime() {
        // D15：T−20 按「吃了」算準時
        assertEquals(OfficialStatus.TAKEN, status(listOf(EventInput(DoseEventType.TAKEN, t - 20 * min)), t + 60 * min))
    }

    @Test
    fun takenAfterOverdueIsLate() {
        assertEquals(OfficialStatus.TAKEN_LATE, status(listOf(EventInput(DoseEventType.LATE_TAKEN, t + 45 * min)), t + 60 * min))
    }

    @Test
    fun byTimeWithoutEvents() {
        assertEquals(OfficialStatus.UPCOMING, status(emptyList(), t - min))
        assertEquals(OfficialStatus.DUE, status(emptyList(), t + 10 * min))
        assertEquals(OfficialStatus.OVERDUE, status(emptyList(), t + 31 * min))
        assertEquals(OfficialStatus.MISSED, status(emptyList(), deadline + min))
    }

    @Test
    fun offlineDeviceIsUnknownNotMissed() {
        // D14：凱比離線期間先不判定
        assertEquals(OfficialStatus.UNKNOWN_DEVICE_OFFLINE, status(emptyList(), deadline + min, online = false))
        assertEquals(OfficialStatus.UNKNOWN_DEVICE_OFFLINE,
            status(listOf(EventInput(DoseEventType.NOT_REMINDED_DEVICE_OFF, deadline + 2 * min)), deadline + 3 * min))
    }

    @Test
    fun lateArrivingTakenEventFixesStatus() {
        // T+29 按「吃了」、T+31 才送到：以按下的時間判定
        assertEquals(OfficialStatus.TAKEN, status(listOf(EventInput(DoseEventType.TAKEN, t + 29 * min)), t + 31 * min))
    }

    @Test
    fun overdueAlertWaitsTwoMinutes() {
        assertFalse(DoseStatusRules.overdueAlertDue(OfficialStatus.OVERDUE, t, t + 31 * min))
        assertTrue(DoseStatusRules.overdueAlertDue(OfficialStatus.OVERDUE, t, t + 32 * min))
        assertFalse(DoseStatusRules.overdueAlertDue(OfficialStatus.TAKEN, t, t + 40 * min))
    }

    @Test
    fun appNotForegroundAndOffline() {
        assertTrue(DoseStatusRules.appNotForegroundAlertDue(t, true, t + 30 * min))
        assertFalse(DoseStatusRules.appNotForegroundAlertDue(t, true, t + 29 * min))
        assertFalse(DoseStatusRules.appNotForegroundAlertDue(t, false, t + 60 * min))
        assertTrue(DoseStatusRules.deviceOffline(t, t + 35 * min))
        assertFalse(DoseStatusRules.deviceOffline(t, t + 10 * min))
    }
}

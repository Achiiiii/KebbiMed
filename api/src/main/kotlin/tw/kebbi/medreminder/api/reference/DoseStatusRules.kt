package tw.kebbi.medreminder.api.reference

import tw.kebbi.medreminder.api.Rules
import tw.kebbi.medreminder.api.dto.DoseEventType

/** 後端判定的正式狀態（D5：後端依事件重算，作為正式紀錄和通知照服員的依據）。 */
enum class OfficialStatus {
    /** 還沒到時間。 */
    UPCOMING,
    /** 提醒中（T 到 T＋30）。 */
    DUE,
    /** 準時吃（含 T−30 起提前吃，D15）。 */
    TAKEN,
    /** 晚吃：逾時之後、晚吃期限之前吃。 */
    TAKEN_LATE,
    /** 逾時未服（T＋30 還沒吃，D23）。 */
    OVERDUE,
    /** 漏服：過了晚吃期限還沒吃（D23）。 */
    MISSED,
    /** 未知（裝置離線）：凱比關機錯過，或凱比離線期間沒有事件（D14）。 */
    UNKNOWN_DEVICE_OFFLINE,
}

data class EventInput(val type: DoseEventType, val occurredAt: Long)

/**
 * 正式狀態與警示的判定。參考實作，常數見 [Rules]。
 *
 * - 以事件的 `occurredAt`（凱比按下的時間）判定，不是送到的時間。事件晚到時重算即可。
 * - 凱比離線期間（沒有心跳）先不判定逾時、漏服，等事件補傳後再算（D14）。
 */
object DoseStatusRules {

    private const val MIN = 60_000L

    fun status(
        scheduledAt: Long,
        lateDeadline: Long,
        events: List<EventInput>,
        now: Long,
        deviceOnline: Boolean,
    ): OfficialStatus {
        val taken = events.filter { it.type == DoseEventType.TAKEN || it.type == DoseEventType.LATE_TAKEN }
            .minByOrNull { it.occurredAt }
        if (taken != null) {
            return if (taken.occurredAt <= scheduledAt + Rules.OVERDUE_AFTER_MIN * MIN) OfficialStatus.TAKEN
            else OfficialStatus.TAKEN_LATE
        }
        if (events.any { it.type == DoseEventType.NOT_REMINDED_DEVICE_OFF }) return OfficialStatus.UNKNOWN_DEVICE_OFFLINE
        val byTime = when {
            now < scheduledAt -> OfficialStatus.UPCOMING
            now < scheduledAt + Rules.OVERDUE_AFTER_MIN * MIN -> OfficialStatus.DUE
            now < lateDeadline -> OfficialStatus.OVERDUE
            else -> OfficialStatus.MISSED
        }
        val needsDevice = byTime == OfficialStatus.OVERDUE || byTime == OfficialStatus.MISSED
        return if (needsDevice && !deviceOnline && events.isEmpty()) OfficialStatus.UNKNOWN_DEVICE_OFFLINE else byTime
    }

    /** 該不該發「逾時未服」的 LINE：逾時後再等 [Rules.LINE_NOTIFY_DELAY_MIN] 分鐘，讓晚一點送到的「吃了」趕上。 */
    fun overdueAlertDue(status: OfficialStatus, scheduledAt: Long, now: Long): Boolean =
        status == OfficialStatus.OVERDUE &&
            now >= scheduledAt + (Rules.OVERDUE_AFTER_MIN + Rules.LINE_NOTIFY_DELAY_MIN) * MIN

    /** 該不該發「漏服」的 LINE。 */
    fun missedAlertDue(status: OfficialStatus): Boolean = status == OfficialStatus.MISSED

    /** 凱比離線：超過 [Rules.DEVICE_OFFLINE_AFTER_MIN] 分鐘沒有心跳。 */
    fun deviceOffline(lastHeartbeatAt: Long?, now: Long): Boolean =
        lastHeartbeatAt == null || now - lastHeartbeatAt >= Rules.DEVICE_OFFLINE_AFTER_MIN * MIN

    /** App 不在前景：心跳回報的 `foreground` 連續 [Rules.APP_NOT_FOREGROUND_ALERT_MIN] 分鐘都是 false（第 7.4 節）。 */
    fun appNotForegroundAlertDue(lastForegroundAt: Long?, deviceOnline: Boolean, now: Long): Boolean =
        deviceOnline && lastForegroundAt != null && now - lastForegroundAt >= Rules.APP_NOT_FOREGROUND_ALERT_MIN * MIN
}

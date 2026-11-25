package tw.kebbi.medreminder.data

import kotlinx.serialization.Serializable
import tw.kebbi.medreminder.api.Slot
import tw.kebbi.medreminder.api.dto.CatalogDto
import tw.kebbi.medreminder.api.dto.ElderDto
import tw.kebbi.medreminder.api.dto.MedicationDto
import tw.kebbi.medreminder.api.dto.ReminderSettingsDto
import tw.kebbi.medreminder.api.dto.RewardsDto
import tw.kebbi.medreminder.api.reference.RoutineMinutes

/**
 * 一包藥在凱比上的狀態。
 * - PENDING：還沒到時間。
 * - REMINDING：提醒中（T 起）。
 * - SNOOZED：按了「等一下」。
 * - OVERDUE：逾時未服（T＋30 還沒吃），晚吃期限前還可以吃。
 * - TAKEN：吃了（`late` = 晚吃）。
 * - MISSED：漏服（過了晚吃期限）。
 * - NOT_REMINDED：凱比關機期間錯過，開機時已過晚吃期限（D14）。
 */
enum class OccStatus { PENDING, REMINDING, SNOOZED, OVERDUE, TAKEN, MISSED, NOT_REMINDED;

    val isOpen: Boolean get() = this == PENDING || this == REMINDING || this == SNOOZED || this == OVERDUE
}

/** 這包裡的一種藥。顯示用的名稱在合併排程時就存下來，藥被停掉後今天的紀錄還看得到名稱。 */
@Serializable
data class OccItem(
    val medicationId: String,
    val name: String,
    val doseText: String,
    val genericName: String? = null,
)

/** 服藥事件（一包藥）。時間都是毫秒。 */
data class Occurrence(
    val id: String,
    val date: String,
    val slot: Slot,
    val slotLabel: String,
    val scheduledAt: Long,
    val lateDeadline: Long,
    val items: List<OccItem>,
    val status: OccStatus = OccStatus.PENDING,
    val remindedAt: Long? = null,
    val secondReminder: Boolean = false,
    val snoozeUntil: Long? = null,
    val takenAt: Long? = null,
    val late: Boolean = false,
    /** 長者看過「逾時未服」畫面了（按了「等一下再說」），就不再自動跳出來。 */
    val overdueSeen: Boolean = false,
    /** 這包按過幾次「等一下」（最多 `settings.maxSnoozes` 次）。 */
    val snoozeCount: Int = 0,
    /** 後台的提醒參數。不存進資料庫：載入、套用排程時從 [DeviceData.reminderSettings] 帶進來（沒有就用預設值）。 */
    val settings: ReminderSettingsDto = ReminderSettingsDto.DEFAULT,
) {
    /** 「利尿劑、降血壓藥」。同類的兩種藥會寫成「降血壓藥兩種」，不會只念一次（CR-08）。 */
    val medNames: String
        get() = items.groupBy { it.name }.entries.joinToString("、") { (name, list) ->
            if (list.size > 1) "$name${chineseCount(list.size)}種" else name
        }

    /** 給長者看的「哪一包」：早餐後 08:00 的利尿劑。 */
    fun doseText(withTime: Boolean = true): String =
        "$slotLabel${if (withTime) " ${Clock.hm(scheduledAt)} " else ""}的${medNames.ifEmpty { "藥" }}"

    companion object {
        fun chineseCount(n: Int) = when (n) { 2 -> "兩"; 3 -> "三"; 4 -> "四"; 5 -> "五"; else -> n.toString() }
    }
}

/** 裝置的設定和後端給的資料（存在 SharedPreferences，見 [DeviceStore]）。 */
@Serializable
data class DeviceData(
    val hardwareId: String = "",
    val deviceId: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val assigned: Boolean = false,
    val disabled: Boolean = false,
    val elder: ElderDto? = null,
    val routineBreakfast: Int = RoutineMinutes.DEFAULT.breakfast,
    val routineLunch: Int = RoutineMinutes.DEFAULT.lunch,
    val routineDinner: Int = RoutineMinutes.DEFAULT.dinner,
    val routineSleep: Int = RoutineMinutes.DEFAULT.sleep,
    /**
     * 後台的提醒參數（等一下幾分鐘、最多幾次、幾分鐘後逾時），跟著排程更新。
     * null ＝ 還沒從排程拿到（例如 App 剛更新）：先用預設值，同步時不帶 ETag 重拉一次排程。
     */
    val reminderSettings: ReminderSettingsDto? = null,
    val medications: List<MedicationDto> = emptyList(),
    val scheduleVersion: Int? = null,
    val scheduleEtag: String? = null,
    /** 上次拉排程是哪一天。排程視窗每天往後滾，所以每天至少拉一次。 */
    val scheduleFetchedDate: String? = null,
    val catalog: CatalogDto? = null,
    val catalogFetchedDate: String? = null,
    val rewards: RewardsDto? = null,
    /** 照服員在後台改了藥，凱比要跟長者說一聲。 */
    val pendingNotice: String? = null,
    /** 正在提醒中的藥被停掉了（第 2.3 節）：「這個藥已經停了，不用吃」。 */
    val stopNotice: String? = null,
    /** 已經刪過舊版留下的 med_photos（D17）。 */
    val oldPhotosCleaned: Boolean = false,
) {
    val routine: RoutineMinutes get() = RoutineMinutes(routineBreakfast, routineLunch, routineDinner, routineSleep)
    fun withRoutine(r: RoutineMinutes) = copy(routineBreakfast = r.breakfast, routineLunch = r.lunch, routineDinner = r.dinner, routineSleep = r.sleep)
}

/** 給畫面用的整體狀態。 */
data class AppState(
    val device: DeviceData = DeviceData(),
    val occurrences: List<Occurrence> = emptyList(),
    /** outbox 裡還沒送出的件數。 */
    val pendingOutbox: Int = 0,
)

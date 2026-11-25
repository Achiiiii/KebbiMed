package tw.kebbi.medreminder.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import tw.kebbi.medreminder.api.Rules
import tw.kebbi.medreminder.api.Slot
import tw.kebbi.medreminder.api.Weekday

// ---------- B1 GET /devices/me/schedule ----------

/** 作息：三餐和睡覺時間，`HH:mm`。各時段的時刻由這四個推算（[Slot.timeOf]）。 */
@Serializable
data class RoutineDto(
    val breakfast: String,
    val lunch: String,
    val dinner: String,
    val sleep: String,
)

/**
 * 提醒的參數：照服員在後台設定，發布新的排程版本後生效。
 * 凱比收到新版本時，正在提醒中、還沒吃的那包也改用新的參數。
 */
@Serializable
data class ReminderSettingsDto(
    /** 按「等一下」後幾分鐘再提醒（不會晚過逾時時間）。 */
    val snoozeMinutes: Int = Rules.SNOOZE_MIN,
    /** 同一包最多可以按幾次「等一下」；0 ＝ 不顯示「等一下」。 */
    val maxSnoozes: Int = Rules.MAX_SNOOZES,
    /** T 之後幾分鐘還沒吃就記成逾時未服。晚吃期限（`lateDeadline`）至少要到這裡。 */
    val overdueAfterMinutes: Int = Rules.OVERDUE_AFTER_MIN,
) {
    /** 後端給了不合理的值時，限制在可以用的範圍內（凱比不會因此壞掉）。 */
    fun normalized() = ReminderSettingsDto(
        snoozeMinutes = snoozeMinutes.coerceIn(1, 120),
        maxSnoozes = maxSnoozes.coerceIn(0, 10),
        overdueAfterMinutes = overdueAfterMinutes.coerceIn(1, 24 * 60),
    )

    companion object {
        val DEFAULT = ReminderSettingsDto()
    }
}

/** 劑量。`text` 是給長者看的寫法，例如「1 顆」「半顆」。 */
@Serializable
data class DoseDto(
    val amount: Double? = null,
    val unit: String? = null,
    val text: String,
)

/** 藥單上的一個藥（處方項目）。一律對應目錄（D6），不會有自由文字的藥名。 */
@Serializable
data class MedicationDto(
    val medicationId: String,
    val catalogId: String,
    /** 凱比顯示、念出來的名稱，例如「利尿劑」。 */
    val displayName: String,
    val genericName: String? = null,
    val brandName: String? = null,
    val dose: DoseDto,
    val route: String = "oral",
    /** 例如「飯後」。 */
    val instructions: String? = null,
    /** 這個藥在哪些時段吃（給畫面顯示「早餐後、晚餐後」用）。 */
    val slots: List<Slot> = emptyList(),
    /** `slots` 含 `CUSTOM` 時的指定時刻（`HH:mm`，D7）。 */
    val customTimes: List<String> = emptyList(),
    /** 每週一次的藥在星期幾；每天吃的是 null。 */
    val weeklyOn: Weekday? = null,
    /** 吃到哪一天（含，`yyyy-MM-dd`，D38 的「吃幾天」）；`null` ＝ 一直吃。過了這天後端就不要再放進 `medications`。 */
    val endDate: String? = null,
    val imageUrl: String? = null,
)

@Serializable
data class OccurrenceItemDto(
    val medicationId: String,
    val doseText: String,
    /** 選填。藥被停掉、`medications` 裡已經沒有時，凱比還能顯示今天紀錄的名稱。 */
    val catalogId: String? = null,
    val displayName: String? = null,
)

/**
 * 服藥事件（一包藥）。
 * - `occurrenceId` 要穩定：同一長者、同一天、同一時段，在新版本裡沒變的話 ID 要相同，凱比才能保留已吃的狀態。
 * - 同一時刻的多包藥要合併成一個 occurrence，避免同時跳兩個提醒。
 * - `lateDeadline` 由後端依固定規則算好（D10），凱比直接使用。
 */
@Serializable
data class OccurrenceDto(
    val occurrenceId: String,
    val date: String,
    val slot: Slot,
    val slotLabel: String,
    val scheduledAt: String,
    val lateDeadline: String,
    val items: List<OccurrenceItemDto>,
)

/** 這個排程版本是怎麼來的。凱比自己送出的申請（`device_request`）不再另外跟長者說「照服員更新了藥」。 */
@Serializable
enum class ChangeSource {
    @SerialName("initial") INITIAL,
    @SerialName("caregiver") CAREGIVER,
    @SerialName("device_request") DEVICE_REQUEST,
}

/**
 * 排程（唯讀）。回傳今天往前 1 天、往後 7 天的 occurrence。
 * 規則參數固定（D10），不放在回應裡；兩邊共用的常數在 [tw.kebbi.medreminder.api.Rules]。
 */
@Serializable
data class ScheduleDto(
    val version: Int,
    val etag: String,
    val publishedAt: String,
    val effectiveFrom: String,
    val timezone: String,
    /** 給凱比念的變更說明，例如「站點照服員幫您更新了藥。新增：降血糖藥。」 */
    val changeSummary: String? = null,
    val changeSource: ChangeSource? = null,
    val routine: RoutineDto,
    /** 提醒的參數（照服員在後台設定，發布後生效）。沒帶就用 [Rules] 的預設值。 */
    val reminderSettings: ReminderSettingsDto? = null,
    val medications: List<MedicationDto>,
    val occurrences: List<OccurrenceDto>,
    val serverTime: String,
)

// ---------- B2 POST /devices/me/schedule/ack ----------

@Serializable
data class ScheduleAckRequest(
    val version: Int,
    val appliedAt: String,
    /** `applied` 或 `failed`。 */
    val result: String,
    val error: String? = null,
)

@Serializable
data class AckResponse(val serverTime: String)

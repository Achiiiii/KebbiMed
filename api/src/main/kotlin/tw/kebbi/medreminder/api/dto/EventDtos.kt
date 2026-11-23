package tw.kebbi.medreminder.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ---------- C1 POST /devices/me/dose-events ----------

/** 服藥事件的種類（凱比回報的事實）。 */
@Serializable
enum class DoseEventType {
    /** 第一次提醒（畫面已顯示或已發橫幅、已說話）。 */
    @SerialName("reminded") REMINDED,
    /** 再提醒（T＋15，或「等一下」到期）。 */
    @SerialName("reminded_again") REMINDED_AGAIN,
    /** 按了「等一下」，`detail.snoozeUntil`。 */
    @SerialName("snoozed") SNOOZED,
    /** 準時吃。`occurredAt` 早於 `scheduledAt` 表示提前吃（最多 30 分鐘，D15），後端算成準時。 */
    @SerialName("taken") TAKEN,
    /** 逾時之後、晚吃期限之前才吃。 */
    @SerialName("late_taken") LATE_TAKEN,
    /** 凱比判定逾時（後端也會自己算，D5）。 */
    @SerialName("overdue") OVERDUE,
    /** 凱比判定漏服（過了晚吃期限）。 */
    @SerialName("missed") MISSED,
    /** 長者被告知這次不要補吃。 */
    @SerialName("declined_late") DECLINED_LATE,
    /** 語音或畫面無法呈現，例如被凱比臉蓋住。 */
    @SerialName("reminder_failed") REMINDER_FAILED,
    /** 凱比關機期間錯過，開機時已經過了晚吃期限（D14）。後端標成「未知（裝置離線）」。 */
    @SerialName("not_reminded_device_off") NOT_REMINDED_DEVICE_OFF,
}

/** 事件是怎麼產生的。 */
@Serializable
enum class EventSource {
    /** 長者在畫面上按。 */
    @SerialName("touch") TOUCH,
    /** 長者用說的（之後做語音回答時用）。 */
    @SerialName("voice") VOICE,
    /** 橫幅上的按鈕（第 2.6 節）。 */
    @SerialName("notification") NOTIFICATION,
    /** 凱比依時間自己判定（提醒、逾時、漏服）。 */
    @SerialName("system") SYSTEM,
}

@Serializable
data class DoseEventDto(
    /** UUID，凱比產生。後端用它去重，同一批可以安全重送。 */
    val eventId: String,
    val occurrenceId: String,
    val type: DoseEventType,
    /** 事情發生的時間（凱比時間），不是送到的時間。後端依這個判定是否準時。 */
    val occurredAt: String,
    val source: EventSource,
    val detail: JsonObject? = null,
)

@Serializable
data class DoseEventBatchDto(val events: List<DoseEventDto>)

@Serializable
data class RejectedEventDto(val eventId: String, val code: String)

@Serializable
data class DoseEventResultDto(
    val accepted: List<String>,
    /** 已經收過的 eventId（重送）。同一個 occurrence 收到多筆 `taken` 時，第一筆以外的也放這裡。 */
    val duplicates: List<String> = emptyList(),
    /** 例如 occurrence 已被刪除（`UNKNOWN_OCCURRENCE`）。凱比丟掉這些事件。 */
    val rejected: List<RejectedEventDto> = emptyList(),
    /** 這台凱比目前該用的排程版本。和本機不同就立刻拉排程（第 2.4 節第 7 項）。 */
    val scheduleVersion: Int? = null,
    val serverTime: String,
)

// ---------- C2 GET /devices/me/adherence?date=YYYY-MM-DD（選用） ----------

@Serializable
data class AdherenceItemDto(
    val occurrenceId: String,
    val status: String,
    val takenAt: String? = null,
    val late: Boolean = false,
)

@Serializable
data class AdherenceDto(
    val date: String,
    val occurrences: List<AdherenceItemDto>,
)

// ---------- C3 POST /devices/me/app-events（選用） ----------

/** 非服藥類的操作紀錄，例如 `app.start`、`app.exit`、`face.covered`、`voice.error`、`notification.opened`、`upload.too_large`。 */
@Serializable
data class AppEventDto(
    val eventId: String,
    val type: String,
    val occurredAt: String,
    val detail: JsonObject? = null,
)

@Serializable
data class AppEventBatchDto(val events: List<AppEventDto>)

@Serializable
data class AppEventResultDto(
    val accepted: List<String>,
    val serverTime: String,
)

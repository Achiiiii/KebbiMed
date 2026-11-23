package tw.kebbi.medreminder.api.dto

import kotlinx.serialization.Serializable
import tw.kebbi.medreminder.api.Slot
import tw.kebbi.medreminder.api.Weekday

// 後台（照服員網頁）中會影響凱比的操作。凱比不呼叫，見 AdminApi。

@Serializable
data class ElderCreateRequest(
    val displayName: String,
    val stationName: String? = null,
    val routine: RoutineDto? = null,
)

@Serializable
data class ElderSummaryDto(
    val elderId: String,
    val displayName: String,
    val stationName: String? = null,
)

@Serializable
data class ClaimDeviceRequest(
    /** 凱比畫面上顯示的 6 位數配對碼。 */
    val code: String,
    val elderId: String,
)

@Serializable
data class ClaimDeviceResponse(
    val deviceId: String,
    val elderId: String,
)

/** 處方項目。後台的藥單只引用目錄（D6）。 */
@Serializable
data class MedicationOrderDto(
    val orderId: String? = null,
    val catalogId: String,
    /** 沒給就用目錄的預設劑量。 */
    val dose: DoseDto? = null,
    /** 沒給就用目錄的預設時段。 */
    val slots: List<Slot> = emptyList(),
    /** `slots` 含 `CUSTOM` 時的指定時刻 `HH:mm`（D7）。 */
    val customTimes: List<String> = emptyList(),
    val weeklyOn: Weekday? = null,
    val startDate: String? = null,
    /** 吃到哪一天（含）；`null` ＝ 一直吃。 */
    val endDate: String? = null,
    /** 需要時才吃（D38 的「一天 0 次」）：沒有時段、不提醒。這時 `slots` 不看。 */
    val asNeeded: Boolean = false,
)

@Serializable
data class PublishRequest(
    /** `now`（立即）或 `tomorrow`（明天起）。由照服員選（第 2.3 節）。 */
    val effective: String = "now",
    val changeSummary: String? = null,
)

@Serializable
data class PublishResponse(
    val version: Int,
    val effectiveFrom: String,
)

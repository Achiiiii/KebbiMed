package tw.kebbi.medreminder.api

import kotlinx.serialization.Serializable

/**
 * 錯誤回應（RFC 7807 Problem Details，第 6.2 節）。
 * 例：`{ "type": "about:blank", "title": "Device not assigned", "status": 409, "code": "DEVICE_NOT_ASSIGNED", "detail": "…", "traceId": "…" }`
 */
@Serializable
data class ProblemDto(
    val type: String = "about:blank",
    val title: String,
    val status: Int,
    /** 機器看得懂的錯誤碼，見 [ErrorCodes]。 */
    val code: String? = null,
    /** 給人看的說明。422 時凱比會把它念給長者聽，所以要寫成一般人看得懂的中文。 */
    val detail: String? = null,
    val traceId: String? = null,
)

/**
 * 錯誤碼與凱比的處理（第 6.4 節）。
 *
 * | HTTP | code | 凱比的處理 |
 * |---|---|---|
 * | 401 | [TOKEN_EXPIRED] | 用 refresh token 換新；失敗就回到配對畫面，但保留本機排程繼續提醒 |
 * | 403 | [DEVICE_DISABLED] | 停止提醒、刪除本機用藥資料，顯示「這台凱比已停用，請聯絡照服員」 |
 * | 404 | [UNKNOWN_OCCURRENCE] | 事件對應的 occurrence 已被刪除：丟掉該事件並記錄（C1 會放在 `rejected`） |
 * | 409 | [DEVICE_NOT_ASSIGNED] | 回到配對畫面，清掉本機排程 |
 * | 413 | [PAYLOAD_TOO_LARGE] | 照片超過上限：不重試，照片留在本機，用 app-events 回報 `upload.too_large` |
 * | 422 | [INVALID_ROUTINE] 等 | 申請內容不合理：把 `detail` 告訴長者，不重試 |
 * | 429、5xx | — | 指數退避重試，事件留在 outbox |
 */
object ErrorCodes {
    const val TOKEN_EXPIRED = "TOKEN_EXPIRED"
    const val DEVICE_DISABLED = "DEVICE_DISABLED"
    const val UNKNOWN_OCCURRENCE = "UNKNOWN_OCCURRENCE"
    const val DEVICE_NOT_ASSIGNED = "DEVICE_NOT_ASSIGNED"
    const val PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"
    const val INVALID_ROUTINE = "INVALID_ROUTINE"
    const val UNKNOWN_CATALOG_ID = "UNKNOWN_CATALOG_ID"
    const val UNKNOWN_MEDICATION = "UNKNOWN_MEDICATION"
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val PAIRING_CODE_INVALID = "PAIRING_CODE_INVALID"
}

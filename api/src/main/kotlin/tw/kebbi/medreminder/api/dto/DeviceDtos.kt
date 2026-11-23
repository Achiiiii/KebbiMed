package tw.kebbi.medreminder.api.dto

import kotlinx.serialization.Serializable

// ---------- A1 POST /devices/register ----------

/** 凱比第一次啟動時登記。`hardwareId` 是 Android ID；只靠它可能被冒用，所以一定要經過配對碼才會綁定長者（D29）。 */
@Serializable
data class RegisterRequest(
    val hardwareId: String,
    val serialNo: String? = null,
    val model: String,
    val osVersion: String,
    val appVersion: String,
    val appVersionCode: Int,
)

/** `hardwareId` 已經登記過時，回傳同一個 `deviceId` 並重新發 token。 */
@Serializable
data class RegisterResponse(
    val deviceId: String,
    val accessToken: String,
    val refreshToken: String,
    /** accessToken 幾秒後過期。 */
    val expiresIn: Long,
    val assigned: Boolean,
    val serverTime: String,
)

// ---------- A2 POST /devices/me/pairing-code ----------

/** 凱比「等待綁定」畫面顯示的配對碼（D29 (c)）。10 分鐘有效，凱比在到期前自動換新。 */
@Serializable
data class PairingCodeResponse(
    /** 6 位數字。 */
    val code: String,
    val expiresAt: String,
    /** 畫成 QR 給照服員用手機掃，例如 `https://<後台網址>/pair?code=482913`。 */
    val qrText: String,
    val serverTime: String,
)

// ---------- A3 POST /devices/token/refresh ----------

@Serializable
data class TokenRefreshRequest(val refreshToken: String)

@Serializable
data class TokenRefreshResponse(
    val accessToken: String,
    val expiresIn: Long,
    /** 有給就換掉舊的 refresh token。 */
    val refreshToken: String? = null,
)

// ---------- A4 GET /devices/me ----------

@Serializable
data class ElderDto(
    val elderId: String,
    /** 凱比怎麼稱呼長者，例如「林阿公」。只放顯示需要的資訊，不放身分證、病歷號。 */
    val displayName: String,
    val stationName: String? = null,
)

/** 開花（D20）：由後端計算，凱比只顯示。 */
@Serializable
data class RewardsDto(
    /** 累計開了幾朵花（全部吃完的天數）。 */
    val flowers: Int,
    /** 今天是否已經全部吃完。 */
    val todayComplete: Boolean,
)

@Serializable
data class DeviceMeResponse(
    val deviceId: String,
    /** 沒綁定時是 false，凱比顯示配對畫面、不提醒。 */
    val assigned: Boolean,
    val elder: ElderDto? = null,
    val timezone: String = "Asia/Taipei",
    val latestScheduleVersion: Int? = null,
    val rewards: RewardsDto? = null,
    val serverTime: String,
)

// ---------- A5 POST /devices/me/heartbeat ----------

@Serializable
data class DiagnosticsDto(
    val appVersion: String,
    /** 語音狀態，例如 `nuwa_ready`、`nuwa_starting`、`android_tts`、`none`。 */
    val voice: String,
    val exactAlarm: Boolean,
    /** 通知權限（D35：沒有允許的話，橫幅出不來）。 */
    val notificationPermission: Boolean,
    val batteryPct: Int? = null,
    val charging: Boolean? = null,
)

/**
 * 心跳。App 在前景時每 1 分鐘、背景時每 15 分鐘一次（D28）。
 * 後端用它判斷凱比是否離線（D14），以及 App 是否不在前景（第 7.4 節）。
 */
@Serializable
data class HeartbeatRequest(
    val appliedScheduleVersion: Int?,
    val deviceTime: String,
    /** App 的畫面是否在最上層。連續 30 分鐘是 false，後端通知照服員。 */
    val foreground: Boolean,
    /** outbox 裡還沒送出的事件數。 */
    val pendingEvents: Int,
    val diagnostics: DiagnosticsDto,
)

@Serializable
data class CommandDto(val type: String)

@Serializable
data class HeartbeatResponse(
    /** 和凱比的 `appliedScheduleVersion` 不同時，凱比會去拉排程（B1）。 */
    val latestScheduleVersion: Int?,
    val serverTime: String,
    val commands: List<CommandDto> = emptyList(),
)

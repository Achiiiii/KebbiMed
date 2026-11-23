package tw.kebbi.medreminder.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import tw.kebbi.medreminder.api.MealRelation
import tw.kebbi.medreminder.api.PrescriptionSlot

// ---------- R2 POST /devices/me/change-requests ----------

@Serializable
enum class ChangeRequestType {
    /** 新增藥（D24、D38：辨識 → 比對目錄 → 對不到才讓使用者選 → 回答用法 → 送出）。 */
    @SerialName("add_medication") ADD_MEDICATION,
    /** 停藥（也用在「掃錯拿掉」）。 */
    @SerialName("remove_medication") REMOVE_MEDICATION,
    /** 改作息（三餐、睡覺時間）。 */
    @SerialName("change_routine") CHANGE_ROUTINE,
    /** 清空藥單（D27）：等於停掉全部的藥。 */
    @SerialName("clear_medications") CLEAR_MEDICATIONS,
    /** 目錄裡找不到這個藥（D26 附帶題 (ii)）：只留照片當紀錄，不改排程、不送藥名。 */
    @SerialName("unlisted_medication") UNLISTED_MEDICATION,
}

@Serializable
enum class IdentifiedBy {
    @SerialName("qr") QR,
    @SerialName("ocr") OCR,
    @SerialName("user_selected") USER_SELECTED,
}

@Serializable
enum class RemoveReason {
    @SerialName("doctor_said_stop") DOCTOR_SAID_STOP,
    @SerialName("added_by_mistake") ADDED_BY_MISTAKE,
}

/**
 * 加藥時的用法（D38）：長者在凱比上一步一步回答、最後確認過的答案（一天幾次、飯前飯後、每次幾顆、吃幾天）。
 * 凱比會先預填建議：名稱對到目錄、而且有藥袋 QR 格式 A 時用 QR 的值，沒有時用目錄預設（D26）；OCR 抓到的數字不預填。
 * 時段對應規則見 [tw.kebbi.medreminder.api.reference.ChangeRequestRules.slotsFor]。
 */
@Serializable
data class PrescriptionDto(
    /** 每次幾顆（可以是 0.5，半顆）。單位用目錄的 `defaultDose.unit`。 */
    val dosePerTime: Double? = null,
    /** 一天幾次（1–4）；0 ＝ 需要時才吃，不定時提醒。 */
    val timesPerDay: Int? = null,
    /** 藥袋 QR 寫的時段（早、午、晚、睡前）。只在個數和 `timesPerDay` 一樣時才帶。 */
    val slots: List<PrescriptionSlot> = emptyList(),
    /** 飯前 `BEFORE`、飯後 `AFTER`；一天 1 次時也可能是睡前 `AT_BEDTIME`、起床空腹 `ON_WAKE`。沒帶就用目錄的設定。 */
    val mealRelation: MealRelation? = null,
    /** 吃幾天，從後端套用那天算起（含當天，1–365）；`null` ＝ 一直吃，直到停藥。 */
    val durationDays: Int? = null,
    /** `ORAL` 或 `TOPICAL`。 */
    val method: String? = null,
)

/**
 * 變更申請。凱比不能直接改排程（D8）：送出申請 → 後端自動套用並發佈新版本 → 凱比拉回新排程。不需要照服員核准。
 *
 * 依 `type` 帶不同欄位：
 * - `add_medication`：`catalogId`（必填，一定是目錄裡的藥，D6）、`identifiedBy`、`prescription`（凱比一律會帶，D38；沒帶時後端用目錄預設）
 * - `remove_medication`：`medicationId`、`reason`
 * - `change_routine`：`routine`
 * - `clear_medications`、`unlisted_medication`：只有共同欄位
 *
 * 不上傳辨識出來的藥名、QR 原文、OCR 全文（D6）。QR 格式 B 的病歷號、身分證欄位在凱比上解析時就丟掉。
 */
@Serializable
data class ChangeRequestDto(
    /** UUID。同一個 `requestId` 重送時，後端回傳第一次的結果，不重複套用。 */
    val requestId: String,
    val type: ChangeRequestType,
    val createdAt: String,
    val catalogId: String? = null,
    val identifiedBy: IdentifiedBy? = null,
    val prescription: PrescriptionDto? = null,
    val medicationId: String? = null,
    val reason: RemoveReason? = null,
    val routine: RoutineDto? = null,
)

@Serializable
enum class ChangeRequestStatus {
    /** 已套用，`scheduleVersion` 是新版本。凱比立刻拉排程。 */
    @SerialName("applied") APPLIED,
    /** 不用改，例如這個藥已經在藥單裡（`reason` = `ALREADY_ACTIVE`）。 */
    @SerialName("no_change") NO_CHANGE,
    /** 只記錄（`unlisted_medication`）。 */
    @SerialName("recorded") RECORDED,
}

@Serializable
data class ChangeRequestResultDto(
    val requestId: String,
    val status: ChangeRequestStatus,
    val reason: String? = null,
    val scheduleVersion: Int? = null,
    /** `add_medication` 套用後的處方項目 ID（「掃錯拿掉」時用）。 */
    val medicationId: String? = null,
    val serverTime: String,
)

// ---------- R1 POST /devices/me/uploads ----------

/** 照片上傳結果。凱比收到 201 就刪除本機照片（D17）。同一個 `sha256` 重送時回 200 和原本的 `uploadId`。 */
@Serializable
data class UploadResultDto(
    val uploadId: String,
    val sha256: String,
)

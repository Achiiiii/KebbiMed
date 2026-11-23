package tw.kebbi.medreminder.api

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 通用規格。
 *
 * - Base URL：`https://<host>/api/v1/`，全程 HTTPS。
 * - JSON、UTF-8、欄位 camelCase；Kotlin 資料類別的欄位名稱就是 JSON 欄位名稱。
 * - 時刻：ISO 8601 含時區，例如 `2026-11-22T08:00:00+08:00`；日期 `YYYY-MM-DD`；時段時間 `HH:mm`。
 * - 凱比的驗證：`Authorization: Bearer <deviceAccessToken>`（App 由 interceptor 統一加上，介面裡不寫）。
 * - 凱比送出的 POST 都帶 [HEADER_IDEMPOTENCY]（UUID）；後端要去重。
 * - 排程、目錄用 `ETag`／`If-None-Match`，沒變回 304。
 * - 錯誤格式：RFC 7807（[ProblemDto]）。
 * - 每個回應都帶 `serverTime`，凱比用來檢查時鐘差。
 */
object ApiConventions {
    const val BASE_PATH = "api/v1/"
    const val HEADER_AUTHORIZATION = "Authorization"
    const val HEADER_IDEMPOTENCY = "Idempotency-Key"
    const val HEADER_IF_NONE_MATCH = "If-None-Match"
    const val TIMEZONE = "Asia/Taipei"

    val ZONE: ZoneId = ZoneId.of(TIMEZONE)

    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    /** 毫秒 → `2026-11-22T08:00:00+08:00`（到秒）。 */
    fun formatTime(epochMs: Long, zone: ZoneId = ZONE): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone).truncatedTo(ChronoUnit.SECONDS).format(ISO)

    /** `2026-11-22T08:00:00+08:00` → 毫秒。 */
    fun parseTime(text: String): Long = OffsetDateTime.parse(text, ISO).toInstant().toEpochMilli()

    fun formatDate(date: LocalDate): String = date.toString()
    fun parseDate(text: String): LocalDate = LocalDate.parse(text)

    /** 一天中的第幾分鐘 → `HH:mm`。 */
    fun formatHm(minuteOfDay: Int): String {
        val m = ((minuteOfDay % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** `HH:mm` → 一天中的第幾分鐘。 */
    fun parseHm(text: String): Int {
        val p = text.trim().split(":")
        require(p.size == 2) { "時間格式要是 HH:mm" }
        val h = p[0].toInt()
        val m = p[1].toInt()
        require(h in 0..23 && m in 0..59) { "時間超出範圍" }
        return h * 60 + m
    }
}

package tw.kebbi.medreminder.data

import tw.kebbi.medreminder.api.ApiConventions
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * 時間工具。時區固定用台灣（和後端一致，第 6.2 節）。
 * 「今天」以 occurrence 的 `date` 為準，跨午夜不用特別處理（D12）。
 */
object Clock {
    /** 測試用：把時間往前或往後挪（毫秒）。只有 debug 版的「快轉時間」會改它（D27）。 */
    @Volatile var offsetMs: Long = 0L

    fun nowMs(): Long = System.currentTimeMillis() + offsetMs

    fun zoned(ms: Long = nowMs()): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(ApiConventions.ZONE)

    fun today(ms: Long = nowMs()): LocalDate = zoned(ms).toLocalDate()

    fun todayKey(ms: Long = nowMs()): String = today(ms).toString()

    fun tomorrowKey(ms: Long = nowMs()): String = today(ms).plusDays(1).toString()

    /** 一天中的第幾分鐘。 */
    fun minuteOfDay(ms: Long = nowMs()): Int = zoned(ms).let { it.hour * 60 + it.minute }

    /** 08:05 這種寫法。 */
    fun hm(ms: Long): String = zoned(ms).let { "%02d:%02d".format(it.hour, it.minute) }

    fun fmt(minute: Int): String = ApiConventions.formatHm(minute)

    /** 真實時間（AlarmManager 用）：把快轉的時間換回系統時間。 */
    fun toRealMs(ms: Long): Long = ms - offsetMs
}

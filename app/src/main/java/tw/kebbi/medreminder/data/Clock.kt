package tw.kebbi.medreminder.data

import java.util.Calendar

/**
 * 時間工具。用 Calendar（不用 java.time），因為凱比的 Android 版本較舊。
 * 「分鐘」一律指一天中的第幾分鐘（0–1439）。
 */
object Clock {
    /** 測試用：把時間往前或往後挪（毫秒）。正式使用保持 0。 */
    @Volatile var offsetMs: Long = 0L

    fun nowMs(): Long = System.currentTimeMillis() + offsetMs

    fun cal(ms: Long = nowMs()): Calendar = Calendar.getInstance().apply { timeInMillis = ms }

    /** 一天中的第幾分鐘（含小數，秒數換算）。 */
    fun minuteOfDay(ms: Long = nowMs()): Double {
        val c = cal(ms)
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60.0
    }

    fun dayKey(ms: Long = nowMs()): String {
        val c = cal(ms)
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    fun dayOfWeek(ms: Long = nowMs()): Int = cal(ms).get(Calendar.DAY_OF_WEEK)

    /** 今天 00:00 的毫秒。 */
    fun startOfDay(ms: Long = nowMs()): Long = cal(ms).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 今天第 m 分鐘的毫秒時間。 */
    fun todayAt(minute: Int, ms: Long = nowMs()): Long = startOfDay(ms) + minute * 60_000L

    fun fmt(minute: Int): String {
        val m = ((minute % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    fun parse(hhmm: String): Int {
        val p = hhmm.split(":")
        return p[0].trim().toInt() * 60 + p[1].trim().toInt()
    }

    fun hms(ms: Long = nowMs()): String {
        val c = cal(ms)
        return "%02d:%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
    }
}

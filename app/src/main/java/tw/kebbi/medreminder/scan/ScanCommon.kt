package tw.kebbi.medreminder.scan

import android.util.Log

/** 使用方式（QR 格式 A 的「口服／外用」）。 */
enum class UsageMethod(val label: String) {
    ORAL("口服"),
    TOPICAL("外用"),
}

/** QR、OCR 擷取到的時段（只有早、午、晚、睡前）。 */
enum class TimeSlot(val label: String) {
    MORNING("早"),
    NOON("午"),
    EVENING("晚"),
    BEDTIME("睡前"),
}

/** 從 QRcode_demo 搬過來的選項範圍（每日次數 1～4，和後端 D26 的對應規則一致）。 */
object WizardOptions {
    val TIMES_PER_DAY = 1..4
}

/**
 * 掃描模組的 log 入口（`adb logcat -s KebbiScan`）。
 * 個資規則（QR 移植文件第 6 節）：只能記數量、長度、耗時、類型。
 * 禁止傳入 QR 原始字串、OCR 全文、ML Kit 的 Barcode/Text 物件，以及含這些內容的物件。
 */
object AppLog {
    const val TAG = "KebbiScan"
    fun d(component: String, message: String) { Log.d(TAG, "[$component] $message") }
    fun i(component: String, message: String) { Log.i(TAG, "[$component] $message") }
    fun w(component: String, message: String, t: Throwable? = null) {
        if (t == null) Log.w(TAG, "[$component] $message") else Log.w(TAG, "[$component] $message", t)
    }
    fun e(component: String, message: String, t: Throwable? = null) {
        if (t == null) Log.e(TAG, "[$component] $message") else Log.e(TAG, "[$component] $message", t)
    }
}

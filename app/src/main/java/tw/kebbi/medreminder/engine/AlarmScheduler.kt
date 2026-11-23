package tw.kebbi.medreminder.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import tw.kebbi.medreminder.data.Clock

/**
 * 只排「下一個轉換點」的鬧鐘（提醒、再提醒、逾時、晚吃期限）。
 * 用 setExactAndAllowWhileIdle（CR-19）：不會在狀態列顯示鬧鐘圖示，也不會有「下一個鬧鐘」。
 */
object AlarmScheduler {
    private const val REQ = 1001
    @Volatile private var lastScheduled: Long? = -1L

    /** [atMs] 是 App 的時間（debug 版快轉時會差一段）；null 表示不需要鬧鐘。 */
    fun schedule(context: Context, atMs: Long?) {
        if (atMs == lastScheduled) return
        lastScheduled = atMs
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, REQ, Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (atMs == null) { am.cancel(pi); return }
        val real = Clock.toRealMs(atMs) + 500 // 多半秒，確保鬧鐘響時已經到點
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, real, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, real, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, real, pi)
        }
    }

    /** 讓下一次 [schedule] 一定會重排（開機、更新 App 之後，系統的鬧鐘可能已經不見了）。 */
    fun invalidate() { lastScheduled = -1L }
}

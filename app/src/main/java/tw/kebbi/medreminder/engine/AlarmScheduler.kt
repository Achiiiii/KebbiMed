package tw.kebbi.medreminder.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import tw.kebbi.medreminder.MainActivity
import tw.kebbi.medreminder.data.AppData

/**
 * 用 AlarmManager 設定「下一次要叫醒 App 的時間」。
 * 就算 App 不在前景、凱比螢幕關著，時間到也會叫醒並跳出提醒。
 */
object AlarmScheduler {
    private const val REQ = 1001

    fun scheduleNext(context: Context, d: AppData) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = ReminderEngine.nextWakeMs(d)
        val pi = PendingIntent.getBroadcast(
            context, REQ, Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // 沒有精準鬧鐘權限時退而求其次（可能晚幾分鐘）
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                // setAlarmClock 最準，而且會在系統顯示鬧鐘圖示
                val show = PendingIntent.getActivity(
                    context, REQ + 1, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
            }
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

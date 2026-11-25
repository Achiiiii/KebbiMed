package tw.kebbi.medreminder.engine

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import tw.kebbi.medreminder.MainActivity
import tw.kebbi.medreminder.R
import tw.kebbi.medreminder.data.Clock
import tw.kebbi.medreminder.data.OccStatus
import tw.kebbi.medreminder.data.Occurrence

/**
 * 橫幅通知。
 *
 * - App 在前景時不發（畫面和語音已經在提醒）；不在前景時才發。進出前景時切換，同一時間只有一個地方在提醒。
 * - 每一包藥（occurrence）一則通知，用 occurrenceId 當 tag。狀態改變時重發同一則，內容和按鈕跟著換。
 * - 按鈕「吃了」「等一下」交給 [NotificationActionReceiver]，不打開 App。
 * - 設 `setTimeoutAfter(晚吃期限)`：過了晚吃期限系統自動收掉。
 * - 不用全螢幕通知（CR-03、D31）。漏服不發橫幅（CR-28）。
 */
object Notifier {
    const val CHANNEL = "med_reminder_v3"
    private const val ID = 1
    const val EXTRA_OCC_ID = "occurrenceId"
    const val EXTRA_FROM_NOTIFICATION = "fromNotification"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        // 舊版用過的頻道（有全螢幕通知）一併刪掉
        listOf("med_reminder_v2").forEach { runCatching { nm.deleteNotificationChannel(it) } }
        val ch = NotificationChannel(CHANNEL, "吃藥提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "時間到提醒吃藥、還沒吃、逾時未服（App 不在畫面上時才會出現）"
            enableVibration(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
        }
        nm.createNotificationChannel(ch)
    }

    fun onEngineEvent(context: Context, e: EngineEvent, foreground: Boolean) {
        when (e) {
            is EngineEvent.Fire, is EngineEvent.RemindAgain, is EngineEvent.Overdue -> if (!foreground) post(context, e.occ)
            is EngineEvent.Missed, is EngineEvent.NotReminded -> cancel(context, e.occ.id)
        }
    }

    /** App 進出前景：回到前景收掉所有提醒中的橫幅；離開前景時有提醒中的就立刻補發（CR-41）。 */
    fun onForegroundChanged(context: Context, foreground: Boolean, occs: List<Occurrence>, now: Long = Clock.nowMs()) {
        val active = occs.filter { (it.status == OccStatus.REMINDING || it.status == OccStatus.OVERDUE) && now < it.lateDeadline }
        if (foreground) occs.forEach { cancel(context, it.id) } else active.forEach { post(context, it) }
    }

    /** 依這包藥目前的狀態發（或更新）橫幅。 */
    fun post(context: Context, o: Occurrence, now: Long = Clock.nowMs()) {
        val (title, body) = when (o.status) {
            OccStatus.REMINDING ->
                if (o.secondReminder) "${o.doseText()}還沒吃喔" to "現在吃也可以，吃完請按「吃了」。"
                else "${o.doseText()}該吃了" to "吃完請按「吃了」。"
            OccStatus.OVERDUE -> "${o.doseText()}還沒吃" to "已經過了 ${Speech.duration(o.settings.overdueAfterMinutes)}，現在吃也可以。吃完請按「吃了」（記成晚吃）。"
            else -> return
        }
        val b = base(context, o.id, title, body)
            .setTimeoutAfter((o.lateDeadline - now).coerceAtLeast(1_000L))
            .addAction(0, "吃了", action(context, NotificationActionReceiver.ACTION_TAKE, o.id))
        if (ReminderEngine.canSnooze(o, now)) b.addAction(0, "等一下", action(context, NotificationActionReceiver.ACTION_SNOOZE, o.id))
        notify(context, o.id, b)
    }

    /** 沒有按鈕的說明（例如「這次不要吃了」「這個藥已經停了」），10 分鐘後自動收掉。 */
    fun postInfo(context: Context, occId: String, title: String, body: String) {
        val b = base(context, occId, title, body).setTimeoutAfter(10 * 60_000L).setOnlyAlertOnce(true)
        notify(context, occId, b)
    }

    fun cancel(context: Context, occId: String) {
        NotificationManagerCompat.from(context).cancel(occId, ID)
    }

    private fun base(context: Context, occId: String, title: String, body: String): NotificationCompat.Builder {
        ensureChannel(context)
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OCC_ID, occId)
            .putExtra(EXTRA_FROM_NOTIFICATION, true)
        val pi = PendingIntent.getActivity(context, ("open:$occId").hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_medreminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pi)
            .setAutoCancel(true)
    }

    private fun action(context: Context, action: String, occId: String): PendingIntent {
        val i = Intent(context, NotificationActionReceiver::class.java).setAction(action).putExtra(EXTRA_OCC_ID, occId)
        return PendingIntent.getBroadcast(context, ("$action:$occId").hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notify(context: Context, occId: String, b: NotificationCompat.Builder) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try { NotificationManagerCompat.from(context).notify(occId, ID, b.build()) } catch (_: SecurityException) { }
    }
}

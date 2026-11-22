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
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import tw.kebbi.medreminder.MainActivity
import tw.kebbi.medreminder.R
import tw.kebbi.medreminder.data.AppData
import tw.kebbi.medreminder.data.DoseStatus
import tw.kebbi.medreminder.data.Slot

/**
 * 系統通知。所有提醒事件都從 Repo.tick() 進來，不管是誰觸發的（鬧鐘、App 自己的計時、開機）。
 *
 * 以前只有鬧鐘那條路會發通知：如果 App 的計時或 Application.onCreate 先跑到提醒時間，
 * 「時間到」的事件就被吃掉了，結果只剩漏吃那一則。現在統一在這裡發。
 *
 * - 每個時段用自己的通知編號：同一包藥從「該吃了」→「還沒吃」→「沒吃到」會更新同一則，不會互相蓋掉別包。
 * - App 正在畫面最前面時不發（畫面和語音已經在提醒），吃了之後把那則收掉。
 */
object Notifier {
    const val CHANNEL = "med_reminder_v2"
    const val TITLE = "用藥提醒"

    /** App 是否正顯示在最前面（MainActivity 的 onResume / onPause 設定）。 */
    @Volatile var foreground = false

    private fun id(s: Slot) = 100 + s.ordinal

    fun onEvents(context: Context, d: AppData, events: List<EngineEvent>) {
        for (e in events) when (e) {
            is EngineEvent.Fire -> {
                wake(context)
                if (!foreground) post(context, e.slot, "${ReminderEngine.doseText(d, e.slot, withTime = true)}該吃了",
                    "吃完請打開 App 按「吃了」。", urgent = true)
            }
            is EngineEvent.Repeat -> {
                wake(context)
                if (!foreground) post(context, e.slot, "${ReminderEngine.doseText(d, e.slot, withTime = true)}還沒吃喔",
                    "現在吃也可以，吃完請按「吃了」。", urgent = true)
            }
            is EngineEvent.Missed -> if (!foreground)
                post(context, e.slot, "${ReminderEngine.doseText(d, e.slot, withTime = true)}還沒吃",
                    "已經過了 ${Rules.MISS_AFTER_MIN} 分鐘，已通知照服員。現在吃也可以，吃完請打開 App 按「吃了」。", urgent = true)
            is EngineEvent.Lapsed ->
                post(context, e.slot, "${ReminderEngine.doseText(d, e.slot, withTime = true)}漏服了",
                    "已經過了可以晚吃的時間，這次就不要吃了，下次照常吃，不要一次吃兩份。", urgent = false)
            else -> {}
        }
    }

    /** 已經吃了（或補吃）的時段，把通知收掉。 */
    fun clearTaken(context: Context, d: AppData) {
        val nm = NotificationManagerCompat.from(context)
        d.slots.forEach { (s, st) -> if (st.status == DoseStatus.TAKEN) nm.cancel(id(s)) }
    }

    /** 試著把 App 叫到前景（凱比上通常可以；手機背景限制較嚴時改靠全螢幕通知）。 */
    private fun wake(context: Context) {
        if (foreground) return
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try { context.startActivity(open) } catch (_: Exception) { }
    }

    private fun channel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL, "吃藥提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "時間到提醒吃藥、還沒吃、沒吃到"
            enableVibration(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
        }
        nm.createNotificationChannel(ch)
    }

    private fun post(context: Context, slot: Slot, title: String, body: String, urgent: Boolean) {
        channel(context)
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(context, id(slot), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_medreminder)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(TITLE)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(if (urgent) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pi)
            .setAutoCancel(true)
        if (urgent) b.setFullScreenIntent(pi, true)
        val canPost = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (canPost) try { NotificationManagerCompat.from(context).notify(id(slot), b.build()) } catch (_: SecurityException) { }
    }
}

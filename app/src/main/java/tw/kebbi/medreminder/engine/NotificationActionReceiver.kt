package tw.kebbi.medreminder.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tw.kebbi.medreminder.api.dto.EventSource
import tw.kebbi.medreminder.data.AppScope
import tw.kebbi.medreminder.data.Clock
import tw.kebbi.medreminder.data.Repo
import tw.kebbi.medreminder.robot.Robot
import tw.kebbi.medreminder.sync.SyncManager

/**
 * 橫幅上的「吃了」「等一下」。不打開 App：
 * 依按下時的狀態判斷 → 寫進本機和 outbox → 立刻送出（送不出去由 WorkManager 在有網路時補送）→ 凱比用語音回應。
 * Android 12 起這裡不能再打開畫面，所以「已過晚吃期限」這類情況只能改橫幅文字。
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Repo.init(context)
        val occId = intent.getStringExtra(Notifier.EXTRA_OCC_ID) ?: return
        val now = Clock.nowMs()
        Repo.tick(now)
        when (intent.action) {
            ACTION_TAKE -> when (Repo.take(occId, EventSource.NOTIFICATION, now)) {
                TakeOutcome.TAKEN, TakeOutcome.LATE_TAKEN -> Robot.say(Speech.TAKEN_BY_NOTIFICATION)
                TakeOutcome.ALREADY_TAKEN -> Notifier.cancel(context, occId)
                TakeOutcome.TOO_LATE -> {
                    Notifier.postInfo(context, occId, "已經過了可以晚吃的時間", "這次就不要吃了，下次照常吃，不要一次吃兩份。")
                    Robot.say(Speech.TOO_LATE)
                }
                TakeOutcome.NOT_FOUND -> Notifier.postInfo(context, occId, "這個藥已經停了", "不用吃。")
                TakeOutcome.TOO_EARLY -> Notifier.cancel(context, occId)
            }
            ACTION_SNOOZE -> {
                val o = Repo.find(occId)
                val until = Repo.snooze(occId, EventSource.NOTIFICATION, now)
                if (until != null && o != null) Robot.say(Speech.snoozed(((until - now + 59_999) / 60_000).toInt()))
                else Repo.find(occId)?.let { Notifier.post(context, it, now) }
            }
        }
        val pending = goAsync()
        AppScope.io.launch {
            try { withTimeoutOrNull(8_000) { SyncManager.flush() } } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_TAKE = "tw.kebbi.medreminder.action.TAKE"
        const val ACTION_SNOOZE = "tw.kebbi.medreminder.action.SNOOZE"
    }
}

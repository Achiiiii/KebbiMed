package tw.kebbi.medreminder.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tw.kebbi.medreminder.data.Repo

/** 鬧鐘響了：跑一次引擎（通知由 Repo.tick → Notifier 統一發），再排下一次。 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Repo.init(context)
        Repo.tick()
        AlarmScheduler.scheduleNext(context, Repo.value)
    }
}

package tw.kebbi.medreminder.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tw.kebbi.medreminder.data.Repo

/** 鬧鐘響了：跑一次引擎（通知、語音、事件都由 Repo.tick 統一處理），再排下一次。 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Repo.init(context)
        AlarmScheduler.invalidate()
        Repo.tick()
    }
}

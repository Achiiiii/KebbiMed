package tw.kebbi.medreminder.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tw.kebbi.medreminder.data.Repo

/** 開機、更新 App、改系統時間後，重新排鬧鐘。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Repo.init(context)
        Repo.tick()
        AlarmScheduler.scheduleNext(context, Repo.value)
    }
}

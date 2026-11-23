package tw.kebbi.medreminder.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tw.kebbi.medreminder.data.AppScope
import tw.kebbi.medreminder.data.Repo
import tw.kebbi.medreminder.sync.SyncManager
import tw.kebbi.medreminder.sync.SyncWorker

/**
 * 開機、App 更新（D35：用裝置管理推送更新後）、改系統時間後：
 * 重排鬧鐘，處理關機期間錯過的（D14），並立刻送一次心跳，讓後端知道 App 不在前景（第 7.4 節）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Repo.init(context)
        AlarmScheduler.invalidate()
        Repo.tick()
        SyncWorker.schedulePeriodic(context)
        Repo.logAppEvent(
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED -> "device.boot"
                Intent.ACTION_MY_PACKAGE_REPLACED -> "app.updated"
                else -> "device.time_changed"
            }
        )
        val pending = goAsync()
        AppScope.io.launch {
            try { withTimeoutOrNull(9_000) { SyncManager.syncAll("boot") } } finally { pending.finish() }
        }
    }
}

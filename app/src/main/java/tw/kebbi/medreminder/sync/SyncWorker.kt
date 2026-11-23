package tw.kebbi.medreminder.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import tw.kebbi.medreminder.api.Rules
import tw.kebbi.medreminder.data.Repo
import java.util.concurrent.TimeUnit

/**
 * 背景同步（D28）：
 * - 每 15 分鐘一次（Android 規定的最短間隔）：心跳、檢查排程、補送事件。
 * - 有待送的事件時排一次「有網路就跑」的工作：網路恢復後系統自己叫起 App 補送，不用等 App 開啟（第 2.6 節）。
 * 前提是 App 沒有被強制停止（第 2.4 節）。
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Repo.init(applicationContext)
        SyncManager.init(applicationContext)
        Repo.tick()
        SyncManager.syncAll("worker")
        return if (Repo.value.pendingOutbox > 0 && !SyncManager.isOnline()) Result.retry() else Result.success()
    }

    companion object {
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(Rules.HEARTBEAT_BACKGROUND_MIN.toLong(), TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("kebbi-sync-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** 有待送的東西：等有網路就送。 */
        fun kick(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("kebbi-sync-once", ExistingWorkPolicy.KEEP, req)
        }
    }
}

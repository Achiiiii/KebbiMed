package tw.kebbi.medreminder

import android.app.Application
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tw.kebbi.medreminder.api.Rules
import tw.kebbi.medreminder.data.AppForeground
import tw.kebbi.medreminder.data.AppScope
import tw.kebbi.medreminder.data.Clock
import tw.kebbi.medreminder.data.Repo
import tw.kebbi.medreminder.engine.Notifier
import tw.kebbi.medreminder.robot.Robot
import tw.kebbi.medreminder.scan.ScanModule
import tw.kebbi.medreminder.sync.SyncManager
import tw.kebbi.medreminder.sync.SyncWorker
import java.io.File

class KebbiApp : Application(), CameraXConfig.Provider {
    private var foregroundLoop: Job? = null

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        SyncManager.init(this)
        Robot.voice = Robot.create(this)
        Notifier.ensureChannel(this)
        SyncWorker.schedulePeriodic(this)
        cleanOldPhotos()

        // App 進出前景（被凱比臉蓋住也算離開）：切換提醒方式（第 2.6 節），前景時每 1 分鐘同步（D28）
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = setForeground(true)
            override fun onStop(owner: LifecycleOwner) = setForeground(false)
        })

        Repo.tick()
        AppScope.io.launch { SyncManager.syncAll("app.start") }
        AppScope.io.launch { ScanModule.warmUp(this@KebbiApp) }
    }

    private fun setForeground(fg: Boolean) {
        AppForeground.state.value = fg
        Notifier.onForegroundChanged(this, fg, Repo.value.occurrences, Clock.nowMs())
        Robot.voice.lockSystemUi(fg)
        foregroundLoop?.cancel()
        if (fg) foregroundLoop = AppScope.io.launch {
            while (isActive) {
                SyncManager.syncAll("foreground")
                // 還沒綁定時每 15 秒查一次（第 6.3 節 A2），綁定後每 1 分鐘（D28）
                val sec = if (Repo.value.device.assigned) Rules.HEARTBEAT_FOREGROUND_SEC else Rules.PAIRING_POLL_SEC
                delay(sec * 1000L)
            }
        } else AppScope.io.launch { SyncManager.syncAll("background") }
    }

    /** D17：舊版存在 files/med_photos/ 的照片沒有對應的後台紀錄，改版時直接刪除。 */
    private fun cleanOldPhotos() {
        if (Repo.value.device.oldPhotosCleaned) return
        File(filesDir, "med_photos").deleteRecursively()
        Repo.updateDevice { it.copy(oldPhotosCleaned = true) }
    }

    /**
     * 凱比只有前鏡頭，系統卻宣告了後鏡頭，CameraX 會一直重試、預覽全黑。
     * 沒有後鏡頭時把可用鏡頭限制為前鏡頭（QR 移植文件 4.4，不要移除）。
     */
    override fun getCameraXConfig(): CameraXConfig {
        val builder = CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig()).setMinimumLoggingLevel(Log.WARN)
        val facings = try {
            val cm = getSystemService(CameraManager::class.java)
            cm.cameraIdList.mapNotNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) }.toSet()
        } catch (_: Exception) { emptySet() }
        if (CameraCharacteristics.LENS_FACING_BACK !in facings && CameraCharacteristics.LENS_FACING_FRONT in facings) {
            builder.setAvailableCamerasLimiter(CameraSelector.DEFAULT_FRONT_CAMERA)
        }
        return builder.build()
    }
}

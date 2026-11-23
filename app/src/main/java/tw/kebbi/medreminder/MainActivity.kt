package tw.kebbi.medreminder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import tw.kebbi.medreminder.data.Repo
import tw.kebbi.medreminder.engine.Notifier
import tw.kebbi.medreminder.ui.AppViewModel
import tw.kebbi.medreminder.ui.KebbiRoot

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // App 在最上層時，螢幕保持亮著，凱比也不會換成凱比臉（第 7.2 節實測）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        requestNotificationPermission()
        handleIntent(intent)
        setContent { KebbiRoot(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        vm.onResume()
    }

    /** 通知權限（Android 13 起要使用者允許）：沒有允許的話橫幅出不來（D35）。 */
    fun requestNotificationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /** 從橫幅打開：記一筆 C3 `notification.opened`（第 2.6 節）。畫面依當下狀態決定，不看通知帶來的內容。 */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(Notifier.EXTRA_FROM_NOTIFICATION, false) == true) {
            Repo.logAppEvent("notification.opened", mapOf("occurrenceId" to (intent.getStringExtra(Notifier.EXTRA_OCC_ID) ?: "")))
            intent.removeExtra(Notifier.EXTRA_FROM_NOTIFICATION)
        }
    }

    /** CR-32：改用 WindowInsetsControllerCompat 隱藏狀態列。 */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

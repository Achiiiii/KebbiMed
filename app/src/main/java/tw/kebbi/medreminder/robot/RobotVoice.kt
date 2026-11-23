package tw.kebbi.medreminder.robot

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nuwarobotics.service.IClientId
import com.nuwarobotics.service.agent.NuwaRobotAPI
import com.nuwarobotics.service.agent.RobotEventCallback
import com.nuwarobotics.service.agent.VoiceEventCallback
import java.util.Locale

/** 凱比說話、做動作的介面。 */
interface RobotVoice {
    /** 給照服員設定頁顯示的狀態。 */
    val status: String get() = ""
    /** 給心跳回報的代碼（`nuwa_ready`、`nuwa_starting`、`android_tts`、`none`）。 */
    val code: String get() = "none"
    fun speak(text: String)
    fun stop()
    /** 凱比的肢體動作（D16 決定提醒先不做動作）。 */
    fun motion(name: Motion) {}
    /** App 在前景時擋住電源鍵和下拉選單，離開時還原（第 7.3 節第 2 項）。 */
    fun lockSystemUi(locked: Boolean) {}
    fun release() {}
}

enum class Motion { REMIND, CHEER, IDLE }

object Robot {
    var voice: RobotVoice = SilentVoice
    fun say(text: String) = voice.speak(spoken(text))

    /** 長者一按按鈕、或換頁，就先閉嘴。 */
    fun hush() { try { voice.stop() } catch (_: Throwable) { } }

    /** 一整頁要說的話：上方文字＋可以按的按鈕。 */
    fun sayScreen(header: String, options: List<String>) {
        val opts = options.distinct()
        val tail = when {
            opts.isEmpty() -> ""
            opts.size == 1 -> "請按「${opts[0]}」。"
            opts.size <= 4 -> "可以按：" + opts.joinToString("、") + "。"
            else -> "請選一個。"
        }
        val h = header.trim().let { if (it.isEmpty() || it.last() in "。？！?!") it else "$it。" }
        say(h + tail)
    }

    /** 選語音：凱比上有原廠服務就用 Nuwa SDK，沒有（模擬器）就用 Android 語音。 */
    fun create(context: Context): RobotVoice =
        if (NuwaVoice.isKebbi(context)) NuwaVoice(context) else AndroidTtsVoice(context)

    /**
     * 把畫面上的文字改成好唸的說法：
     * 08:15 → 早上8點15分、・／ → 停頓、（半顆）→ 半顆、拿掉引號等符號。
     */
    fun spoken(text: String): String {
        var t = Regex("""(\d{1,2}):(\d{2})""").replace(text) { m ->
            val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
            val part = when { h < 5 -> "凌晨"; h < 11 -> "早上"; h < 13 -> "中午"; h < 18 -> "下午"; else -> "晚上" }
            val h12 = if (h > 12) h - 12 else if (h == 0) 12 else h
            part + if (min == 0) "${h12}點" else if (min == 30) "${h12}點半" else "${h12}點${min}分"
        }
        t = t.replace("・", "，").replace("／", "、").replace("/", "、")
            .replace("（", "，").replace("）", "").replace("(", "，").replace(")", "")
            .replace("「", "").replace("」", "").replace("『", "").replace("』", "")
            .replace("−", "減").replace("＋", "加")
            .replace(Regex("，{2,}"), "，")
        return t
    }
}

object SilentVoice : RobotVoice {
    override fun speak(text: String) {}
    override fun stop() {}
}

/**
 * 凱比原廠語音（Nuwa SDK，直接編譯呼叫，D4、CR-37）。
 * - 整個 App 共用這一個 [NuwaRobotAPI]（CR-38）：listener 只註冊一次。
 * - 說話用 `startTTS(text)`，機器人預設就是台灣國語（CR-36）。
 * - 接上 `onTTSComplete`（CR-39）。
 * aar 不放公開 repo：開發者自己從 https://dss.nuwarobotics.com/ 下載放進 `app/libs/`。
 */
class NuwaVoice(context: Context) : RobotVoice {
    private val main = Handler(Looper.getMainLooper())
    private val api = NuwaRobotAPI(context.applicationContext, IClientId(context.packageName))
    @Volatile private var ready = false
    private var pending: String? = null
    @Volatile var speaking = false
        private set

    override var status by mutableStateOf("凱比原廠語音啟動中…")
        private set
    override val code: String get() = if (ready) "nuwa_ready" else "nuwa_starting"

    init {
        api.registerRobotEventListener(object : RobotEventCallback() {
            override fun onWikiServiceStart() = main.post {
                ready = true
                status = "使用凱比原廠語音（Nuwa SDK），已就緒。"
                applyLock() // App 啟動時服務還沒連上，這時候才真的套用
                pending?.let { speak(it); pending = null }
            }.let { }
            override fun onWikiServiceStop() = main.post { ready = false; status = "凱比原廠語音服務停止，等它重新啟動。" }.let { }
            override fun onWikiServiceCrash() = main.post { ready = false; status = "凱比原廠語音服務中斷，等它重新啟動。" }.let { }
            override fun onWikiServiceRecovery() = main.post { ready = true; status = "使用凱比原廠語音（Nuwa SDK），已恢復。" }.let { }
        })
        api.registerVoiceEventListener(object : VoiceEventCallback() {
            override fun onTTSComplete(isError: Boolean) { speaking = false }
        })
        main.postDelayed({ if (!ready) status = "凱比原廠語音沒有回應（等了 15 秒）。請確認這台是凱比，且 SDK 版本相符。" }, 15_000)
    }

    override fun speak(text: String) {
        if (!ready) { pending = text; return }
        try {
            api.stopTTS() // startTTS 會排隊，不會自己打斷上一句
            speaking = true
            api.startTTS(text)
        } catch (e: Throwable) {
            speaking = false
            status = "說話失敗：${e.javaClass.simpleName}"
            Log.w("Voice", "startTTS 失敗", e)
        }
    }

    override fun stop() {
        pending = null
        if (ready) runCatching { api.stopTTS() }
        speaking = false
    }

    @Volatile private var wantLocked = false

    override fun lockSystemUi(locked: Boolean) {
        wantLocked = locked
        if (ready) applyLock()
    }

    private fun applyLock() {
        runCatching {
            if (wantLocked) { api.disablePowerKey(); api.disableSystemAlertUI() }
            else { api.enablePowerKey(); api.enableSystemAlertUI() }
            Log.i("Voice", "電源鍵、下拉選單：${if (wantLocked) "擋住" else "還原"}")
        }.onFailure { Log.w("Voice", "鎖定電源鍵／下拉選單失敗", it) }
    }

    override fun release() { runCatching { api.release() } }

    companion object {
        /** 有凱比原廠服務才是凱比。 */
        fun isKebbi(context: Context): Boolean = try {
            context.packageManager.getPackageInfo("com.nuwarobotics.service", 0); true
        } catch (_: PackageManager.NameNotFoundException) { false }
    }
}

/**
 * Android 內建語音（只給模擬器測試用，CR-31）。凱比上沒有 Android TTS 引擎。
 */
class AndroidTtsVoice(context: Context) : RobotVoice {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    override var status by mutableStateOf("語音啟動中…")
        private set
    override val code: String get() = if (ready) "android_tts" else "none"

    init {
        tts = TextToSpeech(context.applicationContext) { st ->
            if (st == TextToSpeech.SUCCESS) {
                val t = tts ?: return@TextToSpeech
                if (t.setLanguage(Locale.TAIWAN) < 0) t.setLanguage(Locale.CHINESE)
                t.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                t.setSpeechRate(0.9f)
                ready = true
                status = "使用 Android 語音（模擬器測試用）。"
                pending?.let { speak(it); pending = null }
            } else {
                status = "這台機器沒有語音引擎。"
            }
        }
    }

    override fun speak(text: String) {
        if (!ready) { pending = text; return }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kebbi")
    }

    override fun stop() { if (ready) tts?.stop() }
    override fun release() { tts?.shutdown() }
}

package tw.kebbi.medreminder.robot

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.speech.tts.TextToSpeech
import android.util.Log
import java.lang.reflect.Proxy
import java.util.Locale

/**
 * 凱比說話、做動作的介面。
 * 先用 Android 內建語音（TextToSpeech）；拿到 Nuwa 的 SDK 後，
 * 把 NuwaVoice 補完，並在 KebbiApp 改成 Robot.voice = NuwaVoice(this)。
 */
interface RobotVoice {
    /** 給「照服員設定」顯示的語音狀態。 */
    val status: String get() = ""
    fun speak(text: String)
    fun stop()
    /** 凱比的肢體動作，例如提醒時揮手、吃完藥時歡呼。沒有 SDK 時不做事。 */
    fun motion(name: Motion) {}
    fun release() {}
}

enum class Motion { REMIND, CHEER, IDLE }

object Robot {
    var voice: RobotVoice = SilentVoice
    var enabled: Boolean = true
    fun say(text: String) { if (enabled) voice.speak(spoken(text)) }

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

    /**
     * 把畫面上的文字改成好唸的說法：
     * 08:15 → 8點15分、08:00 → 8點、「・」「／」→ 停頓、（半顆）→ 半顆、拿掉引號等符號。
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
 * Android 內建語音（台灣國語）。
 *
 * 啟動時會依序試這台機器上的每一個語音引擎（先試預設的），每個等 6 秒；
 * 哪一個先回應就用哪一個。全部都沒回應時，狀態會寫出試過哪些引擎，方便跟廠商確認。
 */
class AndroidTtsVoice(context: Context) : RobotVoice {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private val tried = mutableListOf<String>()
    private var queue: MutableList<String?> = mutableListOf()

    /** 給「照服員設定」顯示的語音狀態（Compose 會自動更新畫面）。 */
    override var status by mutableStateOf("語音啟動中…")
        private set

    init {
        // 先用 null（系統預設引擎），再試每一個找得到的引擎
        val probe = TextToSpeech(app) { }
        val engines = try { probe.engines.map { it.name } } catch (_: Exception) { emptyList() }
        try { probe.shutdown() } catch (_: Exception) { }
        queue = (listOf<String?>(null) + engines).toMutableList()
        status = "語音啟動中…（找到的引擎：${engines.ifEmpty { listOf("無") }.joinToString("、")}）"
        tryNext()
    }

    private fun tryNext() {
        if (queue.isEmpty()) {
            status = "語音引擎都沒有回應（試過：${tried.joinToString("、").ifEmpty { "無" }}）。" +
                "這台機器的語音可能只開放給原廠 SDK 使用，需要改用機器人原廠語音 SDK。"
            Log.w("Voice", status)
            return
        }
        val engine = queue.removeAt(0)
        tried += engine ?: "系統預設"
        var answered = false
        val timeout = Runnable {
            if (!answered) { answered = true; try { tts?.shutdown() } catch (_: Exception) { }; tryNext() }
        }
        val listener = TextToSpeech.OnInitListener { st ->
            main.post {
                if (answered) return@post
                answered = true
                main.removeCallbacks(timeout)
                if (st == TextToSpeech.SUCCESS) onReady() else { try { tts?.shutdown() } catch (_: Exception) { }; tryNext() }
            }
        }
        tts = if (engine == null) TextToSpeech(app, listener) else TextToSpeech(app, listener, engine)
        main.postDelayed(timeout, 6000)
    }

    private fun onReady() {
        val t = tts ?: return
        val engine = t.defaultEngine ?: "未知"
        var r = t.setLanguage(Locale.TAIWAN)
        var lang = "中文（台灣）"
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            r = t.setLanguage(Locale.CHINESE); lang = "中文"
        }
        val langNote = if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            // 有些機器人的語音引擎不回報支援中文，但其實會說：照樣用引擎預設語言說
            val fallback = t.defaultVoice?.locale ?: Locale.getDefault()
            t.setLanguage(fallback)
            lang = "引擎預設語言（$fallback）"
            "（引擎說不支援中文，改用預設語言）"
        } else ""
        // 用媒體音量播放（有些機器的「語音」音量是關著的）
        t.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        t.setSpeechRate(0.9f)
        status = "語音已啟動：引擎 ${tried.last()}（$engine），$lang$langNote。按「測試說話」聽聽看。"
        ready = true
        pending?.let { speak(it); pending = null }
    }

    override fun speak(text: String) {
        if (!ready) { pending = text; return }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kebbi")
    }

    override fun stop() { if (ready) tts?.stop() }
    override fun release() { tts?.shutdown() }
}

/**
 * 凱比原廠語音（Nuwa SDK）。
 *
 * 用法：把 Nuwa 的 SDK 檔案（例如 NuwaSDK-2021-07-08_1058_2.1.0.08_e21fe7.aar）放進 app/libs/，
 * 重新打包就會自動改用凱比原本的聲音，不用改程式。沒放檔案時，App 會用 Android 內建語音。
 *
 * 這裡用反射呼叫 SDK，所以沒有 aar 也能編譯。呼叫方式依 Nuwa 官方範例
 * （github.com/nuwarobotics/NuwaSDKExample 的 voice/TTSActivity.java）：
 *   IClientId(packageName) → NuwaRobotAPI(context, clientId) → registerRobotEventListener
 *   → 等 onWikiServiceStart() → startTTS(text)
 */
class NuwaVoice(context: Context) : RobotVoice {
    private val main = Handler(Looper.getMainLooper())
    private var api: Any? = null
    private var ready = false
    private var pending: String? = null

    override var status by mutableStateOf("凱比原廠語音啟動中…")
        private set

    init {
        try {
            val idCls = Class.forName("com.nuwarobotics.service.IClientId")
            val apiCls = Class.forName("com.nuwarobotics.service.agent.NuwaRobotAPI")
            val lisCls = Class.forName("com.nuwarobotics.service.agent.RobotEventListener")
            val clientId = idCls.getConstructor(String::class.java).newInstance(context.packageName)
            val a = apiCls.getConstructor(Context::class.java, idCls).newInstance(context.applicationContext, clientId)
            val listener = Proxy.newProxyInstance(lisCls.classLoader, arrayOf(lisCls)) { proxy, m, args ->
                when (m.name) {
                    "onWikiServiceStart" -> main.post {
                        ready = true
                        status = "使用凱比原廠語音（Nuwa SDK），已就緒。按「測試說話」聽聽看。"
                        pending?.let { speak(it); pending = null }
                    }
                    "onWikiServiceStop", "onWikiServiceCrash" -> main.post { ready = false; status = "凱比原廠語音服務中斷（${m.name}），等它重新啟動。" }
                    "onWikiServiceRecovery" -> main.post { ready = true; status = "使用凱比原廠語音（Nuwa SDK），已恢復。" }
                    "equals" -> return@newProxyInstance proxy === args?.get(0)
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                    "toString" -> return@newProxyInstance "NuwaVoiceListener"
                }
                defaultValue(m.returnType)
            }
            apiCls.getMethod("registerRobotEventListener", lisCls).invoke(a, listener)
            api = a
            main.postDelayed({ if (!ready) status = "凱比原廠語音沒有回應（等了 15 秒）。請確認這台是凱比，且 SDK 版本相符。" }, 15000)
        } catch (e: Throwable) {
            status = "凱比原廠語音啟動失敗：${e.javaClass.simpleName} ${e.message ?: ""}"
            Log.w("Voice", "Nuwa SDK 啟動失敗", e)
        }
    }

    override fun speak(text: String) {
        val a = api ?: return
        if (!ready) { pending = text; return }
        // 先停掉上一句（Nuwa 的 startTTS 會排隊，不會自己打斷）
        try { a.javaClass.getMethod("stopTTS").invoke(a) } catch (_: Throwable) { }
        // 指定中文，否則可能用英文發音去唸中文字
        try { a.javaClass.getMethod("startTTS", String::class.java, String::class.java).invoke(a, text, Locale.CHINESE.toString()) }
        catch (e: Throwable) { status = "startTTS 失敗：${e.message}"; Log.w("Voice", "startTTS 失敗", e) }
    }

    override fun stop() {
        val a = api ?: return
        try { a.javaClass.getMethod("stopTTS").invoke(a) } catch (_: Throwable) { }
    }

    override fun release() {
        val a = api ?: return
        try { a.javaClass.getMethod("release").invoke(a) } catch (_: Throwable) { }
    }

    companion object {
        /** app/libs/ 裡有 Nuwa SDK 才會是 true。 */
        fun isAvailable(): Boolean = try {
            Class.forName("com.nuwarobotics.service.agent.NuwaRobotAPI"); true
        } catch (_: Throwable) { false }

        private fun defaultValue(t: Class<*>): Any? = when (t) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> 0.toChar()
            else -> null
        }
    }
}

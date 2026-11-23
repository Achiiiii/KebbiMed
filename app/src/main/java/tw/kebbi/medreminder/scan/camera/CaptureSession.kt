package tw.kebbi.medreminder.scan.camera

import tw.kebbi.medreminder.scan.qr.QrDetection
import tw.kebbi.medreminder.scan.qr.QrKind
import java.io.File

enum class CaptureSource(val label: String) {
    AUTO("自動拍照"),
    /** 一段時間沒看到 QR，倒數三秒自動拍（沒有 QR 的藥袋，D39）。 */
    COUNTDOWN("倒數拍照"),
    CAMERA("手動拍照"),
    TEST_IMAGE("測試圖片"),
}

/** 即時偵測看到的 QR（只放記憶體）。個資規則：toString 不含原始字串。 */
class LiveQr(val raw: String, val kind: QrKind, val lastSeenMs: Long) {
    override fun toString(): String = "LiveQr(kind=$kind, len=${raw.length})"
}

/** 最近幾秒內即時偵測到的 QR，拍照後與原圖偵測結果合併（格式 B 的小 QR 有時只有即時預覽解得出來）。 */
class LiveQrCache {
    private val map = LinkedHashMap<String, LiveQr>()

    @Synchronized
    fun record(items: List<Pair<String, QrKind>>, nowMs: Long) {
        items.forEach { (raw, kind) -> map[raw] = LiveQr(raw, kind, nowMs) }
        map.values.removeAll { nowMs - it.lastSeenMs > KEEP_MS }
    }

    @Synchronized
    fun recent(nowMs: Long, windowMs: Long = RECENT_WINDOW_MS): List<LiveQr> =
        map.values.filter { nowMs - it.lastSeenMs <= windowMs }

    @Synchronized
    fun clear() = map.clear()

    companion object {
        const val RECENT_WINDOW_MS = 2_000L
        private const val KEEP_MS = 10_000L
    }
}

/** 相機頁 → 辨識之間傳遞的這次取圖（只放記憶體，不放進 Intent 或導航參數）。 */
class PendingCapture(
    val file: File,
    val source: CaptureSource,
    val capturedAtMs: Long,
    val liveRecent: List<LiveQr>,
    /** 相機頁拍完已經對原圖做過的 QR 偵測（避免辨識時重做）。 */
    val precomputed: QrDetection? = null,
) {
    override fun toString(): String = "PendingCapture(source=$source, live=${liveRecent.size})"
}

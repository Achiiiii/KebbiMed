package tw.kebbi.medreminder.scan.camera

/**
 * 自動拍照的觸發判斷（純 Kotlin，不依賴 android.*）。
 *
 * - 進入相機頁（[reset]）後先等 [armDelayMs]，讓使用者對準、相機完成對焦。
 * - 「有可辨識資訊的 QR」（格式 A 或 B）連續出現達 [stableMs] 才觸發，避免拍到模糊的畫面。
 * - 偶爾漏掉幾幀（≤ [gapToleranceMs]）不算中斷。
 * - 每次 [reset] 之後最多觸發一次，避免連拍。
 */
class AutoCaptureGate(
    private val armDelayMs: Long = 1_500,
    private val stableMs: Long = 600,
    private val gapToleranceMs: Long = 300,
) {
    private var armedAt: Long = Long.MAX_VALUE
    private var stableSince: Long? = null
    private var lastSeen: Long? = null
    private var fired = false

    fun reset(nowMs: Long, armDelayMs: Long = this.armDelayMs) {
        armedAt = nowMs + armDelayMs
        stableSince = null
        lastSeen = null
        fired = false
    }

    /** 每一幀呼叫；回傳 true 表示現在要自動拍照。 */
    fun onFrame(nowMs: Long, hasInfoQr: Boolean): Boolean {
        if (fired) return false
        val last = lastSeen
        if (hasInfoQr) {
            if (stableSince == null || (last != null && nowMs - last > gapToleranceMs)) stableSince = nowMs
            lastSeen = nowMs
        } else if (last != null && nowMs - last > gapToleranceMs) {
            stableSince = null
            lastSeen = null
        }
        // 只在「這一幀確實看得到 QR」時觸發，漏幀容忍期間不拍
        if (!hasInfoQr || nowMs < armedAt) return false
        val since = stableSince ?: return false
        if (nowMs - since >= stableMs) {
            fired = true
            return true
        }
        return false
    }

    /** 給 UI 顯示的進度 0..1；0 表示目前沒有在追蹤 QR。 */
    fun progress(nowMs: Long): Float {
        val since = stableSince ?: return 0f
        if (fired) return 1f
        val start = maxOf(since, armedAt - stableMs)
        return ((nowMs - start).toFloat() / stableMs).coerceIn(0.01f, 1f)
    }

    val hasFired: Boolean get() = fired
}

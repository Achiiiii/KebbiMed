package tw.kebbi.medreminder.scan.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCaptureGateTest {

    /** 以 50ms 一幀餵資料，回傳第一次觸發的時間（沒觸發回傳 null）。 */
    private fun AutoCaptureGate.run(from: Long, to: Long, hasQr: (Long) -> Boolean): Long? {
        var t = from
        while (t <= to) {
            if (onFrame(t, hasQr(t))) return t
            t += 50
        }
        return null
    }

    @Test
    fun firesAfterStableQr_onceArmed() {
        val gate = AutoCaptureGate(armDelayMs = 1_500, stableMs = 600)
        gate.reset(0)
        // QR 從 2000ms 開始一直出現 → 2600ms 觸發
        assertEquals(2_600L, gate.run(0, 5_000) { it >= 2_000 })
    }

    @Test
    fun doesNotFireBeforeArmDelay() {
        val gate = AutoCaptureGate(armDelayMs = 1_500, stableMs = 600)
        gate.reset(0)
        // 一進來就有 QR：要等到 1500ms 才能觸發
        assertEquals(1_500L, gate.run(0, 5_000) { true })
    }

    @Test
    fun noQr_neverFires() {
        val gate = AutoCaptureGate()
        gate.reset(0)
        assertEquals(null, gate.run(0, 10_000) { false })
    }

    @Test
    fun shortFlash_doesNotFire() {
        val gate = AutoCaptureGate(armDelayMs = 0, stableMs = 600, gapToleranceMs = 300)
        gate.reset(0)
        // QR 只出現 400ms 後消失超過容忍時間
        assertEquals(null, gate.run(0, 3_000) { it in 1_000..1_400 })
    }

    @Test
    fun smallGap_isTolerated() {
        val gate = AutoCaptureGate(armDelayMs = 0, stableMs = 600, gapToleranceMs = 300)
        gate.reset(0)
        // 1000ms 起出現，1200..1400 漏幀（< 300ms）→ 仍在 1600ms 觸發
        assertEquals(1_600L, gate.run(0, 3_000) { it >= 1_000 && it !in 1_200..1_400 })
    }

    @Test
    fun longGap_restartsStableWindow() {
        val gate = AutoCaptureGate(armDelayMs = 0, stableMs = 600, gapToleranceMs = 300)
        gate.reset(0)
        // 1000..1400 出現，1450..1950 消失（> 300ms），2000 起再出現 → 2600 觸發
        assertEquals(2_600L, gate.run(0, 5_000) { it in 1_000..1_400 || it >= 2_000 })
    }

    @Test
    fun firesOnlyOnce_untilReset() {
        val gate = AutoCaptureGate(armDelayMs = 0, stableMs = 600)
        gate.reset(0)
        assertTrue(gate.run(0, 2_000) { true } != null)
        assertTrue(gate.hasFired)
        assertEquals(null, gate.run(2_050, 6_000) { true })
        gate.reset(6_000)
        assertFalse(gate.hasFired)
        assertEquals(6_600L, gate.run(6_000, 9_000) { true })
    }

    @Test
    fun progress_risesWhileTracking() {
        val gate = AutoCaptureGate(armDelayMs = 0, stableMs = 600)
        gate.reset(0)
        assertEquals(0f, gate.progress(0), 0f)
        gate.onFrame(1_000, true)
        gate.onFrame(1_300, true)
        assertEquals(0.5f, gate.progress(1_300), 0.01f)
    }
}

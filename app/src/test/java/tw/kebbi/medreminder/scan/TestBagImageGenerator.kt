package tw.kebbi.medreminder.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kebbi.medreminder.scan.qr.QrClassifier
import tw.kebbi.medreminder.scan.qr.QrKind
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 產生測試用的藥袋圖片（只有假資料）到 `app/build/test-bags/`，給凱比上的 debug「測試圖片」用：
 * `adb push app/build/test-bags/bag-amlodipine.bmp /sdcard/Android/data/tw.kebbi.medreminder/files/test/`
 * 格式 A（大 QR，藥品說明）＋格式 B（小 QR，調劑資料，病歷號、身分證是假的 TEST000001、Z000000000）。
 * Android 單元測試不能用 java.awt，所以自己寫 24 位元的 BMP。
 */
class TestBagImageGenerator {
    private val w = 1280
    private val h = 720

    private fun bag(name: String, a: String?, b: String?) {
        val px = IntArray(w * h) { 0xF4F1E8 }
        fun draw(text: String, x: Int, y: Int, size: Int) {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 2))
            for (i in 0 until size) for (j in 0 until size) px[(y + j) * w + x + i] = if (m[i, j]) 0x000000 else 0xFFFFFF
        }
        if (a != null) draw(a, 720, 120, 480)
        if (b != null) draw(b, 160, 380, 260)
        val rowSize = (w * 3 + 3) / 4 * 4
        val buf = ByteBuffer.allocate(54 + rowSize * h).order(ByteOrder.LITTLE_ENDIAN)
        buf.put('B'.code.toByte()).put('M'.code.toByte()).putInt(54 + rowSize * h).putInt(0).putInt(54)
        buf.putInt(40).putInt(w).putInt(h).putShort(1).putShort(24).putInt(0).putInt(rowSize * h).putInt(2835).putInt(2835).putInt(0).putInt(0)
        for (y in h - 1 downTo 0) {
            for (x in 0 until w) { val c = px[y * w + x]; buf.put((c and 0xFF).toByte()).put((c shr 8 and 0xFF).toByte()).put((c shr 16 and 0xFF).toByte()) }
            repeat(rowSize - w * 3) { buf.put(0) }
        }
        val dir = File("build/test-bags").apply { mkdirs() }
        File(dir, "$name.bmp").writeBytes(buf.array())
    }

    @Test
    fun generate() {
        val a = "Amlodipine 5 mg 每次1粒 口服 每日2次 飯後"
        val b = "20261120;UTR02;TEST000001;Z000000000;56"
        assertTrue(QrClassifier.classify(a) == QrKind.A && QrClassifier.classify(b) == QrKind.B)
        bag("bag-amlodipine", a, b)
        bag("bag-unknown-drug", "測試用藥 10 mg 每次1粒 口服 每日1次", b)
        bag("bag-b-only", null, b)
    }
}

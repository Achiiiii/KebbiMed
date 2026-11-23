package tw.kebbi.medreminder.scan.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class QrClassifierTest {

    // task.md 的四段範例（格式 B 第 3、4 欄為假資料）
    private val sampleA1 = "Utraphen 錠劑 325/37.5 mg 每次1粒 口服 每6小時使用1次 臨床用途為止痛 (但無消炎) " +
        "副作用為便秘，嗜睡，盜汗，噁心，暈眩，呼吸抑制 (呼吸變慢，變淺)。本品可能增加跌倒風險，請注意。"
    private val sampleA2 = "藥包 Topamax 低 膠囊 25 mg 每次1粒 口服 睡前使用 臨床用途為抗癲癇劑、治療偏頭痛 " +
        "副作用為暈眩、頭痛、嗜睡、震顫、疲勞、運動失調等"
    private val sampleB1 = "20260417;UTR02;TEST000001;Z000000000;56"
    private val sampleB2 = "20260423;TOP02;TEST000001;Z000000000;28"

    @Test
    fun formatA_samples() {
        assertEquals(QrKind.A, QrClassifier.classify(sampleA1))
        assertEquals(QrKind.A, QrClassifier.classify(sampleA2))
    }

    @Test
    fun formatB_samples() {
        assertEquals(QrKind.B, QrClassifier.classify(sampleB1))
        assertEquals(QrKind.B, QrClassifier.classify(sampleB2))
        assertEquals(QrKind.B, QrClassifier.classify("  $sampleB1\n"))
    }

    @Test
    fun emptyOrNull_isOther() {
        assertEquals(QrKind.OTHER, QrClassifier.classify(""))
        assertEquals(QrKind.OTHER, QrClassifier.classify("   "))
        assertEquals(QrKind.OTHER, QrClassifier.classify(null))
    }

    @Test
    fun formatB_wrongFieldCount_isOther() {
        assertEquals(QrKind.OTHER, QrClassifier.classify("20260417;UTR02;TEST000001;56"))
        assertEquals(QrKind.OTHER, QrClassifier.classify("20260417;UTR02;TEST000001;Z000000000;56;X"))
    }

    @Test
    fun formatB_badDatePrefix_isOther() {
        assertEquals(QrKind.OTHER, QrClassifier.classify("2026041;UTR02;TEST000001;Z000000000;56"))
        assertEquals(QrKind.OTHER, QrClassifier.classify("ABCDEFGH;UTR02;TEST000001;Z000000000;56"))
    }

    @Test
    fun url_isOther() {
        assertEquals(QrKind.OTHER, QrClassifier.classify("https://example.com/drug?id=123"))
    }

    @Test
    fun meiCiWithoutName_isOther() {
        assertEquals(QrKind.OTHER, QrClassifier.classify("每次1粒 口服"))
        assertEquals(QrKind.OTHER, QrClassifier.classify("  每次1粒 口服"))
    }

    @Test
    fun garbledDetection() {
        assertFalse(QrClassifier.looksGarbled(sampleA1))
        assertFalse(QrClassifier.looksGarbled(sampleB1))
        assertFalse(QrClassifier.looksGarbled(""))
        assertTrue(QrClassifier.looksGarbled("Utraphen ��"))
        // Big5 位元組被誤當 ISO-8859-1 解碼的情況
        val misDecoded = String("每次1粒 口服 睡前使用".toByteArray(Charset.forName("Big5")), Charsets.ISO_8859_1)
        assertTrue(QrClassifier.looksGarbled(misDecoded))
    }
}

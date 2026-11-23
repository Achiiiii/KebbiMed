package tw.kebbi.medreminder.scan.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.Charset

class QrTextTest {

    private val big5 = Charset.forName("Big5")
    private val sampleA = "藥包 Topamax 低 膠囊 25 mg 每次1粒 口服 睡前使用"

    @Test
    fun mlkitValue_usedWhenClean() {
        val d = QrText.bestText(sampleA, sampleA.toByteArray(Charsets.UTF_8))!!
        assertEquals(sampleA, d.text)
        assertEquals(QrText.Source.MLKIT, d.source)
    }

    @Test
    fun nullValue_utf8Bytes() {
        val d = QrText.bestText(null, sampleA.toByteArray(Charsets.UTF_8))!!
        assertEquals(sampleA, d.text)
        assertEquals(QrText.Source.UTF8, d.source)
    }

    @Test
    fun nullValue_big5Bytes() {
        val d = QrText.bestText(null, sampleA.toByteArray(big5))!!
        assertEquals(sampleA, d.text)
        assertEquals(QrText.Source.BIG5, d.source)
        assertEquals(QrKind.A, QrClassifier.classify(d.text))
    }

    @Test
    fun garbledValue_fallsBackToBig5Bytes() {
        val bytes = sampleA.toByteArray(big5)
        val garbled = String(bytes, Charsets.ISO_8859_1)
        val d = QrText.bestText(garbled, bytes)!!
        assertEquals(sampleA, d.text)
        assertEquals(QrText.Source.BIG5, d.source)
    }

    @Test
    fun garbledValue_noBytes_keepsGarbledFlag() {
        val garbled = String(sampleA.toByteArray(big5), Charsets.ISO_8859_1)
        val d = QrText.bestText(garbled, null)!!
        assertEquals(QrText.Source.MLKIT_GARBLED, d.source)
    }

    @Test
    fun nothing_returnsNull() {
        assertNull(QrText.bestText(null, null))
        assertNull(QrText.bestText("", ByteArray(0)))
    }

    @Test
    fun asciiFormatB_unchanged() {
        val b = "20260417;UTR02;TEST000001;Z000000000;56"
        val d = QrText.bestText(b, b.toByteArray())!!
        assertEquals(b, d.text)
        assertEquals(QrText.Source.MLKIT, d.source)
    }
}

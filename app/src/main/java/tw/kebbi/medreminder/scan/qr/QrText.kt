package tw.kebbi.medreminder.scan.qr

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 決定 QR 內容的文字（純 Kotlin）。
 *
 * 台灣醫院系統的 QR 可能用 Big5 編碼且沒有 ECI 標記：
 * ML Kit 可能回傳 rawValue = null，或用錯誤字集解出亂碼。
 * 這時改用 rawBytes 依序嘗試 UTF-8、Big5 的嚴格解碼。
 */
object QrText {

    enum class Source(val label: String) {
        MLKIT("ML Kit"),
        UTF8("UTF-8（rawBytes）"),
        BIG5("Big5（rawBytes）"),
        MLKIT_GARBLED("ML Kit（疑似亂碼）"),
    }

    class Decoded(val text: String, val source: Source) {
        override fun toString(): String = "Decoded(source=$source, len=${text.length})"
    }

    private val BIG5: Charset = Charset.forName("Big5")

    fun bestText(rawValue: String?, rawBytes: ByteArray?): Decoded? {
        if (rawValue != null && rawValue.isNotEmpty() && !QrClassifier.looksGarbled(rawValue)) {
            return Decoded(rawValue, Source.MLKIT)
        }
        if (rawBytes != null && rawBytes.isNotEmpty()) {
            decodeStrict(rawBytes, Charsets.UTF_8)?.takeIf { !QrClassifier.looksGarbled(it) }
                ?.let { return Decoded(it, Source.UTF8) }
            decodeStrict(rawBytes, BIG5)?.takeIf { !QrClassifier.looksGarbled(it) }
                ?.let { return Decoded(it, Source.BIG5) }
        }
        if (!rawValue.isNullOrEmpty()) return Decoded(rawValue, Source.MLKIT_GARBLED)
        return null
    }

    fun decodeStrict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

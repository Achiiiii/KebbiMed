package tw.kebbi.medreminder.scan.ocr

import android.graphics.Bitmap

/** OCR 引擎介面（只保留 ML Kit；不搬 Tesseract）。方便以後換引擎。 */
interface OcrEngine {
    val id: OcrEngineId

    /** 辨識整張圖；[OcrResult.elapsedMs] 只計辨識本身。 */
    suspend fun recognize(bitmap: Bitmap): OcrResult

    /** 暖機：先載入模型，第一次辨識才不會多等。辨識一張小空白圖。 */
    suspend fun warmUp() {
        val blank = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        runCatching { recognize(blank) }
    }

    fun close() {}
}

enum class OcrEngineId(val label: String) {
    MLKIT("ML Kit"),
}

/** 個資規則：toString 不輸出 OCR 全文。 */
class OcrResult(val text: String, val elapsedMs: Long) {
    override fun toString(): String = "OcrResult(len=${text.length}, ms=$elapsedMs)"
}

package tw.kebbi.medreminder.scan.ocr

import android.graphics.Bitmap
import android.os.SystemClock
import tw.kebbi.medreminder.scan.await
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/** ML Kit Text Recognition v2 中文版（bundled，不需要 Google Play 服務）。 */
class MlKitOcrEngine : OcrEngine {

    override val id = OcrEngineId.MLKIT

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        val image = InputImage.fromBitmap(bitmap, 0)
        val start = SystemClock.elapsedRealtime()
        val text = recognizer.process(image).await()
        return OcrResult(text.text, SystemClock.elapsedRealtime() - start)
    }

    override fun close() = recognizer.close()
}

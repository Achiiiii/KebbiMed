package tw.kebbi.medreminder.scan.qr

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import tw.kebbi.medreminder.scan.await
import tw.kebbi.medreminder.scan.AppLog
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.Closeable

/**
 * 偵測到的一個 QR code。
 * 個資規則：[raw] 只能顯示在畫面上，不得寫進 log；[toString] 只輸出種類與長度。
 */
class DetectedQr(
    /** 最終採用的文字（見 [QrText.bestText]）。 */
    val raw: String,
    val kind: QrKind,
    /** 原圖座標（still image）或 PreviewView 座標（即時偵測）。 */
    val box: Rect?,
    val textSource: QrText.Source,
) {
    val looksGarbled: Boolean get() = textSource == QrText.Source.MLKIT_GARBLED

    override fun toString(): String = "DetectedQr(kind=$kind, len=${raw.length}, src=$textSource)"

    companion object {
        fun from(barcode: Barcode): DetectedQr? {
            val decoded = QrText.bestText(barcode.rawValue, barcode.rawBytes) ?: return null
            return DetectedQr(decoded.text, QrClassifier.classify(decoded.text), barcode.boundingBox, decoded.source)
        }

        /** 只記錄數量與長度（個資規則）。 */
        fun logStats(tag: String, barcodes: List<Barcode>) {
            if (barcodes.isEmpty()) return
            val stats = barcodes.joinToString { b ->
                "value=${b.rawValue?.length ?: "null"}/bytes=${b.rawBytes?.size ?: "null"}"
            }
            AppLog.d(tag, "mlkit barcodes=${barcodes.size} [$stats]")
        }
    }
}

class QrDetection(
    val items: List<DetectedQr>,
    val elapsedMs: Long,
    /** ML Kit 有偵測到、但無法取得文字的數量。 */
    val undecodable: Int,
) {
    fun count(kind: QrKind) = items.count { it.kind == kind }
    override fun toString(): String =
        "QrDetection(A=${count(QrKind.A)}, B=${count(QrKind.B)}, other=${count(QrKind.OTHER)}, " +
            "undecodable=$undecodable, ms=$elapsedMs)"
}

/** 對靜態圖片做 QR 偵測（ML Kit bundled，一張圖可回傳多個 QR）。 */
class QrDetector : Closeable {

    private val scanner: BarcodeScanner = newScanner()

    suspend fun detect(bitmap: Bitmap): QrDetection {
        val start = SystemClock.elapsedRealtime()
        val barcodes = scanner.process(InputImage.fromBitmap(bitmap, 0)).await()
        val elapsed = SystemClock.elapsedRealtime() - start
        DetectedQr.logStats("QrDetector", barcodes)
        val decoded = barcodes.mapNotNull { DetectedQr.from(it) }
        val items = decoded.distinctBy { it.raw }
        return QrDetection(items, elapsed, undecodable = barcodes.size - decoded.size)
    }

    override fun close() = scanner.close()

    companion object {
        fun newScanner(): BarcodeScanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
}

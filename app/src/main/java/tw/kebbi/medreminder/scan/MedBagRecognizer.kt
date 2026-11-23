package tw.kebbi.medreminder.scan

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.kebbi.medreminder.api.PrescriptionSlot
import tw.kebbi.medreminder.api.dto.IdentifiedBy
import tw.kebbi.medreminder.api.dto.PrescriptionDto
import tw.kebbi.medreminder.scan.camera.LiveQrCache
import tw.kebbi.medreminder.scan.camera.PendingCapture
import tw.kebbi.medreminder.scan.ocr.MlKitOcrEngine
import tw.kebbi.medreminder.scan.qr.QrDetector
import tw.kebbi.medreminder.scan.qr.QrParseResult
import tw.kebbi.medreminder.scan.qr.QrParser
import java.io.File

/**
 * 藥袋辨識結果（QR 移植文件 4.8）。
 * 個資規則：toString 不輸出任何原文。辨識出來的藥名只用來比對目錄，不上傳（D6）。
 */
class MedBagScan(
    val imageFile: File,
    val qr: QrParseResult,
    val ocr: OcrExtraction?,
) {
    val drugName: String? get() = qr.a?.drugName ?: ocr?.drugName

    /** 名稱是從哪裡來的（申請的 `identifiedBy`）。 */
    val identifiedBy: IdentifiedBy? get() = when {
        qr.a != null -> IdentifiedBy.QR
        ocr?.drugName != null -> IdentifiedBy.OCR
        else -> null
    }

    /** D26 (b)、D38：藥袋 QR 格式 A 的劑量、次數、時段，拿來預填加藥精靈的建議；OCR 抓到的數字不採用。 */
    val prescription: PrescriptionDto? get() = qr.a?.let { a ->
        PrescriptionDto(
            dosePerTime = a.dosePerTime?.toDouble(),
            timesPerDay = a.timesPerDay,
            slots = a.slots.map { PrescriptionSlot.valueOf(it.name) }.sortedBy { it.ordinal },
            method = a.method?.name,
        ).takeIf { it.dosePerTime != null || it.timesPerDay != null || it.slots.isNotEmpty() }
    }

    override fun toString() = "MedBagScan(A=${qr.aCount}, B=${qr.bCount}, ocr=${ocr != null})"
}

/** 辨識流程精簡版（QR 移植文件 4.8）：原圖 QR ＋ 即時偵測的 QR → 解析；沒有格式 A 就跑 OCR。 */
class MedBagRecognizer(
    private val imageStore: ImageStore,
    private val qrDetector: QrDetector,
    private val ocr: MlKitOcrEngine,
) {
    suspend fun recognize(capture: PendingCapture): MedBagScan = withContext(Dispatchers.Default) {
        val full = imageStore.decodeUpright(capture.file) ?: error("無法讀取圖片")
        val detection = capture.precomputed ?: qrDetector.detect(full)
        // 一張照片預設只有一個藥袋：依框的面積由大到小排序，多個 A 時用最大的
        val stillTexts = detection.items
            .sortedByDescending { item -> item.box?.let { it.width().toLong() * it.height() } ?: 0L }
            .map { it.raw }
        val liveOnly = capture.liveRecent.map { it.raw }.filter { it !in stillTexts }
        val parse = QrParser.parseAll(stillTexts + liveOnly)
        val extraction = if (parse.a == null) {
            try {
                ocr.recognize(full).text.takeIf { it.isNotBlank() }?.let { OcrKeywordExtractor.extract(it) }
            } catch (e: Exception) {
                AppLog.w("Scan", "ocr failed: ${e.javaClass.simpleName}")
                null
            }
        } else null
        AppLog.i("Scan", "A=${parse.aCount} B=${parse.bCount} unparsed=${parse.unparsedCount} liveOnly=${liveOnly.size} ocrName=${extraction?.drugName != null}")
        MedBagScan(capture.file, parse, extraction)
    }
}

/** 掃描用的單例（QR 移植文件的 AppContainer）：圖片、QR 偵測、OCR，以及啟動時暖機。 */
object ScanModule {
    @Volatile private var store: ImageStore? = null
    val qrDetector by lazy { QrDetector() }
    val ocr by lazy { MlKitOcrEngine() }
    val liveQrCache = LiveQrCache()

    fun imageStore(context: Context): ImageStore =
        store ?: synchronized(this) { store ?: ImageStore(context.filesDir).also { store = it } }

    fun recognizer(context: Context) = MedBagRecognizer(imageStore(context), qrDetector, ocr)

    /** 啟動時在背景暖機 OCR，第一次辨識才不會多等模型載入。 */
    suspend fun warmUp(context: Context) {
        imageStore(context)
        runCatching { ocr.warmUp() }
    }
}

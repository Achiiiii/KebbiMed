package tw.kebbi.medreminder.scan.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tw.kebbi.medreminder.scan.AppLog
import tw.kebbi.medreminder.scan.ScanModule
import tw.kebbi.medreminder.scan.await
import tw.kebbi.medreminder.scan.qr.DetectedQr
import tw.kebbi.medreminder.scan.qr.QrDetector
import tw.kebbi.medreminder.scan.qr.QrKind
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/*
 * 相機掃藥袋（從 QRcode_demo 的 CameraScreen 移植，計時常數和相機設定都是在凱比上實測調出來的，沒有實測過不要改）：
 * - 偵測到格式 A／B 的 QR 而且穩定 0.6 秒 → 對焦 → 等清晰的一幀 → 拍照 → 原圖讀不到 QR 就自動重拍（最多 2 次）。
 * - 沒有 QR 的藥袋（D39）：一段時間沒看到 QR，就說「我數到三就拍」，倒數三、二、一後自動拍，之後走 OCR。
 *   倒數中看到 QR 就取消倒數，改由 QR 自動拍。長者也可以自己按「拍照」。
 * - 前鏡頭拍照一定要 isReversedHorizontal = false，否則照片是鏡像，QR 和文字都讀不到。
 */

private fun QrKind.color(): Color = when (this) {
    QrKind.A -> Color(0xFF1B8A3A)
    QrKind.B -> Color(0xFF1565C0)
    QrKind.OTHER -> Color(0xFFE65100)
}

private enum class CapturePhase(val banner: String?) {
    IDLE(null),
    FOCUSING("對焦中，請保持不動…"),
    CAPTURING("拍照中，請保持不動…"),
    VERIFYING("檢查照片清晰度…"),
}

private const val FOCUS_TIMEOUT_MS = 2_500L
private const val SHARP_FRAME_TIMEOUT_MS = 1_500L
private const val MAX_AUTO_RETRIES = 2
private const val RETRY_ARM_MS = 800L
/** 相機好了之後（或上一次倒數之後）多久沒看到 QR 就開始倒數。第一次要等凱比念完畫面上的說明。 */
private const val COUNTDOWN_AFTER_MS = 6_000L
/** 「我數到三就拍」念完的時間。 */
private const val COUNTDOWN_INTRO_MS = 1_800L

private fun hasCameraPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * 掃藥袋的相機畫面。拍好後交出 [PendingCapture]（只在記憶體裡傳）。
 * @param onSay 倒數時要凱比說話（「我數到三就拍」「三」「二」「一」）。
 * @param onTestImage debug 版才有：用推到 App 資料夾的測試圖片代替拍照。
 */
@Composable
fun ScanCamera(
    onCaptured: (PendingCapture) -> Unit,
    onPick: () -> Unit,
    onBack: () -> Unit,
    onNoCamera: () -> Unit,
    onSay: (String) -> Unit = {},
    onTestImage: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) onNoCamera()
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(Color(0xFF263A40)), contentAlignment = Alignment.Center) {
        if (granted) CameraContent(onCaptured, onNoCamera, onSay)
        else Text("需要相機權限才能拍藥袋", color = Color.White, fontSize = 32.sp)
        Row(Modifier.align(Alignment.TopEnd).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (onTestImage != null) OverlayPill("測試圖片", onTestImage)
            OverlayPill("改用選的", onPick)
            OverlayPill("返回", onBack)
        }
    }
}

@Composable
private fun OverlayPill(text: String, onClick: () -> Unit) {
    Box(
        Modifier.height(64.dp).clip(RoundedCornerShape(32.dp)).background(Color.White.copy(alpha = .92f))
            .clickable(onClick = onClick).padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 28.sp, color = Color(0xFF3A9D9B), fontWeight = FontWeight.Bold) }
}

@Composable
private fun CameraContent(onCaptured: (PendingCapture) -> Unit, onNoCamera: () -> Unit, onSay: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val controller = remember { LifecycleCameraController(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val liveScanner = remember { QrDetector.newScanner() }
    val imageStore = remember { ScanModule.imageStore(context) }
    val liveCache = ScanModule.liveQrCache

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var liveItems by remember { mutableStateOf<List<DetectedQr>>(emptyList()) }
    var ready by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf(CapturePhase.IDLE) }

    val gate = remember { AutoCaptureGate().also { it.reset(SystemClock.elapsedRealtime()) } }
    var autoProgress by remember { mutableFloatStateOf(0f) }
    var autoFire by remember { mutableStateOf(false) }
    var autoRetries by remember { mutableIntStateOf(0) }
    var lastInfoFrameAt by remember { mutableLongStateOf(0L) }
    /** 倒數中顯示的數字（3、2、1），沒有在倒數是 null。 */
    var countdown by remember { mutableStateOf<Int?>(null) }

    val analyzer = remember {
        MlKitAnalyzer(listOf(liveScanner), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, mainExecutor) { result ->
            val now = SystemClock.elapsedRealtime()
            val items = result.getValue(liveScanner).orEmpty().mapNotNull { DetectedQr.from(it) }
            liveItems = items
            val hasInfoQr = items.any { it.kind != QrKind.OTHER }
            if (hasInfoQr) lastInfoFrameAt = now
            if (items.isNotEmpty()) liveCache.record(items.map { it.raw to it.kind }, now)
            if (phase == CapturePhase.IDLE) {
                if (gate.onFrame(now, hasInfoQr)) autoFire = true
                autoProgress = gate.progress(now)
            } else autoProgress = 0f
        }
    }

    LaunchedEffect(Unit) {
        try {
            val provider = ProcessCameraProvider.getInstance(context).await(mainExecutor)
            val infos = provider.availableCameraInfos
            val chosen = infos.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }
                ?: infos.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
                ?: infos.firstOrNull()
            if (chosen == null) { onNoCamera(); return@LaunchedEffect }
            val aspect = CameraConfig.aspectFor(context, chosen.lensFacing)
            // CameraX 1.4：改其他設定時會用目前的鏡頭重建 use case，預設是後鏡頭（凱比沒有），所以要先指定鏡頭
            controller.cameraSelector = chosen.cameraSelector
            controller.setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
            controller.imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            controller.previewResolutionSelector = CameraConfig.previewSelector(aspect)
            controller.imageCaptureResolutionSelector = CameraConfig.captureSelector(aspect)
            controller.imageAnalysisResolutionSelector = AnalysisResolution.FHD.selector(aspect)
            controller.isPinchToZoomEnabled = false
            controller.isTapToFocusEnabled = true
            controller.setImageAnalysisAnalyzer(analysisExecutor, analyzer)
            controller.bindToLifecycle(lifecycleOwner)
            ready = true
            AppLog.i("Camera", "bound facing=${chosen.lensFacing} aspect=${if (aspect == AspectRatio.RATIO_16_9) "16:9" else "4:3"}")
        } catch (e: Exception) {
            AppLog.e("Camera", "bind failed", e)
            onNoCamera()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            liveScanner.close()
            analysisExecutor.shutdown()
            liveCache.clear()
        }
    }

    suspend fun runCapture(source: CaptureSource) {
        if (phase != CapturePhase.IDLE) return
        message = null
        val startedAt = SystemClock.elapsedRealtime()
        val liveAtStart = liveCache.recent(startedAt)
        val infoBoxes = liveItems.filter { it.kind != QrKind.OTHER }.mapNotNull { it.box }
        try {
            phase = CapturePhase.FOCUSING
            val focusOk = focusOn(controller, previewView, infoBoxes, mainExecutor)
            if (source == CaptureSource.AUTO) {
                val focusDoneAt = SystemClock.elapsedRealtime()
                val sharp = withTimeoutOrNull(SHARP_FRAME_TIMEOUT_MS) {
                    while (lastInfoFrameAt <= focusDoneAt) delay(30)
                    true
                } == true
                if (!sharp) {
                    AppLog.i("Camera", "auto capture aborted: no sharp frame (focusOk=$focusOk)")
                    message = "請拿穩藥袋，對準後會再自動拍照"
                    gate.reset(SystemClock.elapsedRealtime(), RETRY_ARM_MS)
                    return
                }
            }
            phase = CapturePhase.CAPTURING
            val file = imageStore.newImageFile()
            if (!takePicture(controller, file, mainExecutor)) {
                message = "拍照失敗，請再試一次"
                gate.reset(SystemClock.elapsedRealtime(), RETRY_ARM_MS)
                return
            }
            phase = CapturePhase.VERIFYING
            val detection = withContext(Dispatchers.Default) {
                imageStore.decodeUpright(file)?.let { bmp -> runCatching { ScanModule.qrDetector.detect(bmp) }.getOrNull() }
            }
            val infoCount = detection?.let { it.count(QrKind.A) + it.count(QrKind.B) } ?: 0
            AppLog.i("Camera", "capture src=$source focusOk=$focusOk stillInfoQr=$infoCount retries=$autoRetries")
            if (source == CaptureSource.AUTO && infoCount == 0 && autoRetries < MAX_AUTO_RETRIES) {
                imageStore.delete(file) // 模糊的照片刪掉
                autoRetries++
                message = "照片不夠清楚，自動重拍（第 $autoRetries 次）"
                gate.reset(SystemClock.elapsedRealtime(), RETRY_ARM_MS)
                return
            }
            onCaptured(PendingCapture(file, source, startedAt, liveAtStart, precomputed = detection))
        } finally {
            runCatching { controller.cameraControl?.cancelFocusAndMetering() }
            phase = CapturePhase.IDLE
        }
    }

    LaunchedEffect(autoFire) {
        if (autoFire) { autoFire = false; runCapture(CaptureSource.AUTO) }
    }

    // 沒有 QR 的藥袋（D39）：一段時間沒看到 QR 就倒數自動拍；倒數中看到 QR 就讓 QR 自動拍接手
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        var quietSince = SystemClock.elapsedRealtime()
        fun qrSeen() = SystemClock.elapsedRealtime() - lastInfoFrameAt < 500 || autoProgress > 0f
        while (true) {
            delay(200)
            if (phase != CapturePhase.IDLE || qrSeen()) { quietSince = SystemClock.elapsedRealtime(); continue }
            if (SystemClock.elapsedRealtime() - quietSince < COUNTDOWN_AFTER_MS) continue
            AppLog.i("Camera", "no QR for ${COUNTDOWN_AFTER_MS}ms, countdown")
            onSay("我數到三就拍。")
            delay(COUNTDOWN_INTRO_MS)
            var cancelled = qrSeen()
            for (n in 3 downTo 1) {
                if (cancelled) break
                countdown = n
                onSay(listOf("一", "二", "三")[n - 1])
                repeat(5) { delay(200); if (qrSeen() || phase != CapturePhase.IDLE) cancelled = true }
            }
            countdown = null
            if (!cancelled) runCapture(CaptureSource.COUNTDOWN)
            else AppLog.i("Camera", "countdown cancelled: QR seen or manual capture")
            quietSince = SystemClock.elapsedRealtime()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    this.controller = controller
                    previewView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        QrOverlay(liveItems, Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 24.dp, end = 270.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val text = message ?: phase.banner ?: when {
                !ready -> "相機準備中…"
                autoProgress > 0f -> "看到藥袋的 QR Code，請保持不動"
                countdown != null -> "拿著不要動，我要拍了"
                else -> "請把藥袋對準我的眼睛，拿著不要動\n看到 QR Code 馬上拍，沒有的話我數到三就拍"
            }
            Column(Modifier.background(Color(0xCC000000), RoundedCornerShape(16.dp)).padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                if (phase == CapturePhase.IDLE && autoProgress > 0f) {
                    LinearProgressIndicator(progress = { autoProgress }, modifier = Modifier.padding(top = 8.dp).width(320.dp).height(12.dp))
                }
            }
        }
        countdown?.let { n ->
            Box(Modifier.align(Alignment.Center).width(220.dp).height(220.dp).clip(RoundedCornerShape(110.dp)).background(Color(0x993A9D9B)),
                contentAlignment = Alignment.Center) {
                Text("$n", color = Color.White, fontSize = 140.sp, fontWeight = FontWeight.Black)
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(24.dp).height(96.dp).width(220.dp).clip(RoundedCornerShape(48.dp))
                .background(if (phase == CapturePhase.IDLE && ready) Color(0xFF3A9D9B) else Color.Gray)
                .clickable(enabled = phase == CapturePhase.IDLE && ready) { scope.launch { runCapture(CaptureSource.CAMERA) } },
            contentAlignment = Alignment.Center,
        ) { Text("拍照", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold) }
    }
}

/** 對 QR 所在位置（沒有 QR 時對畫面中央）做對焦與測光，鎖定到 cancelFocusAndMetering 為止。 */
private suspend fun focusOn(controller: CameraController, previewView: PreviewView?, boxes: List<Rect>, executor: Executor): Boolean {
    val control = controller.cameraControl ?: return false
    val pv = previewView ?: return false
    if (pv.width == 0 || pv.height == 0) return false
    val (x, y) = if (boxes.isEmpty()) pv.width / 2f to pv.height / 2f else {
        ((boxes.minOf { it.left } + boxes.maxOf { it.right }) / 2f) to ((boxes.minOf { it.top } + boxes.maxOf { it.bottom }) / 2f)
    }
    val point = pv.meteringPointFactory.createPoint(x, y)
    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).disableAutoCancel().build()
    return try {
        withTimeoutOrNull(FOCUS_TIMEOUT_MS) { control.startFocusAndMetering(action).await(executor).isFocusSuccessful } ?: false
    } catch (e: Exception) {
        AppLog.w("Camera", "focus failed: ${e.javaClass.simpleName}")
        false
    }
}

/** 拍照存到私有目錄；前鏡頭明確關閉鏡像。 */
private suspend fun takePicture(controller: CameraController, file: File, executor: Executor): Boolean =
    suspendCancellableCoroutine { cont ->
        val metadata = ImageCapture.Metadata().apply { isReversedHorizontal = false }
        val options = ImageCapture.OutputFileOptions.Builder(file).setMetadata(metadata).build()
        controller.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) { if (cont.isActive) cont.resume(true) }
            override fun onError(exception: ImageCaptureException) {
                AppLog.e("Camera", "capture failed code=${exception.imageCaptureError}", exception)
                file.delete()
                if (cont.isActive) cont.resume(false)
            }
        })
    }

/** 即時偵測框：MlKitAnalyzer 已轉成 PreviewView 座標，overlay 與 PreviewView 同大小。 */
@Composable
private fun QrOverlay(items: List<DetectedQr>, modifier: Modifier) {
    val labelPaint = remember { android.graphics.Paint().apply { textSize = 30f; isAntiAlias = true; isFakeBoldText = true } }
    Canvas(modifier) {
        items.forEach { item ->
            val box = item.box ?: return@forEach
            val color = item.kind.color()
            drawRect(color, Offset(box.left.toFloat(), box.top.toFloat()), Size(box.width().toFloat(), box.height().toFloat()), style = Stroke(width = 6f))
            labelPaint.color = color.toArgb()
            drawContext.canvas.nativeCanvas.drawText(if (item.kind == QrKind.OTHER) "?" else item.kind.name,
                box.left.toFloat(), (box.top - 8f).coerceAtLeast(30f), labelPaint)
        }
    }
}

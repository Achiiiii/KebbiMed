package tw.kebbi.medreminder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 相機掃 QR Code（CameraX + ML Kit）。凱比的鏡頭在臉上，所以先用前鏡頭，沒有再用後鏡頭。
 *
 * 不用按任何按鈕：
 * - 看到 QR Code → onResult（直接加藥）。
 * - 相機開好後 [autoPhotoSec] 秒內都看不到 QR Code → 自動拍下藥袋（藥名那面）→ onPhoto，交給照服員在後台確認。
 */
@Composable
fun QrScanner(onResult: (String) -> Unit, onPhoto: (java.io.File) -> Unit, onNoCamera: () -> Unit, autoPhotoSec: Int = 5) {
    val ctx = LocalContext.current
    @Suppress("DEPRECATION")
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) onNoCamera()
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Text("需要相機權限才能掃描", color = Color.White, fontSize = 32.sp)
        return
    }

    val done = remember { AtomicBoolean(false) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var ready by remember { mutableStateOf(false) }
    var left by remember { mutableStateOf(autoPhotoSec) }
    var shooting by remember { mutableStateOf(false) }
    // 倒數；到 0 還沒掃到 QR Code 就拍照
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        while (left > 0 && !done.get()) { delay(1000); left -= 1 }
        if (done.compareAndSet(false, true)) {
            shooting = true
            val dir = java.io.File(ctx.filesDir, "med_photos").apply { mkdirs() }
            val file = java.io.File(dir, java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".jpg")
            capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(ctx),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(r: ImageCapture.OutputFileResults) { onPhoto(file) }
                    override fun onError(e: ImageCaptureException) { Log.e("QrScanner", "拍照失敗", e); onNoCamera() }
                })
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val providerFuture = remember { ProcessCameraProvider.getInstance(ctx) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                val view = PreviewView(c).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val scanner = BarcodeScanning.getClient(
                        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                    )
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(executor) { proxy -> analyze(proxy, scanner, done, onResultMain = { text ->
                        ContextCompat.getMainExecutor(c).execute { onResult(text) }
                    }) }
                    try {
                        val selector = when {
                            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                            else -> null
                        }
                        if (selector == null) {
                            onNoCamera()
                        } else {
                            provider.unbindAll()
                            provider.bindToLifecycle(owner, selector, preview, analysis, capture)
                            ready = true
                        }
                    } catch (e: Exception) {
                        Log.e("QrScanner", "相機啟動失敗", e)
                        onNoCamera()
                    }
                }, ContextCompat.getMainExecutor(c))
                view
            },
        )
        // 對準框（放大，藥袋整面都可以放進來）
        Box(Modifier.size(520.dp, 440.dp).border(6.dp, Color.White.copy(alpha = .85f), RoundedCornerShape(28.dp)))
        val hint = when {
            shooting -> "拍照中…"
            !ready -> "相機準備中…"
            else -> "看不到 QR Code 的話，$left 秒後幫您拍下藥袋上的字"
        }
        Text(hint, color = Color.White, fontSize = 30.sp,
            modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = .45f), RoundedCornerShape(16.dp)).padding(horizontal = 24.dp, vertical = 10.dp))
    }

    DisposableEffect(Unit) {
        onDispose {
            try { providerFuture.get().unbindAll() } catch (_: Exception) { }
            executor.shutdown()
        }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun analyze(
    proxy: androidx.camera.core.ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    done: AtomicBoolean,
    onResultMain: (String) -> Unit,
) {
    val img = proxy.image
    if (img == null || done.get()) { proxy.close(); return }
    val input = InputImage.fromMediaImage(img, proxy.imageInfo.rotationDegrees)
    scanner.process(input)
        .addOnSuccessListener { codes ->
            val text = codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
            if (text != null && done.compareAndSet(false, true)) onResultMain(text)
        }
        .addOnCompleteListener { proxy.close() }
}

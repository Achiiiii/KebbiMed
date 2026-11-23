package tw.kebbi.medreminder.scan.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalLensFacing
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import kotlin.math.abs

/** 即時 QR 偵測用的分析解析度（小 QR 讀不到時可調高）。 */
enum class AnalysisResolution(val label: String) {
    HD("720p"),
    FHD("1080p"),
    MAX("最高");

    fun selector(aspect: Int): ResolutionSelector {
        val is169 = aspect == AspectRatio.RATIO_16_9
        return ResolutionSelector.Builder()
            .setAspectRatioStrategy(CameraConfig.aspectStrategy(aspect))
            .setResolutionStrategy(
                when (this) {
                    HD -> ResolutionStrategy(
                        if (is169) Size(1280, 720) else Size(1280, 960),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    )
                    FHD -> ResolutionStrategy(
                        if (is169) Size(1920, 1080) else Size(1920, 1440),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    )
                    MAX -> ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
                }
            )
            .build()
    }
}

object CameraConfig {

    fun aspectStrategy(aspect: Int) = AspectRatioStrategy(aspect, AspectRatioStrategy.FALLBACK_RULE_AUTO)

    /** 預覽、拍照、分析用同一個長寬比，預覽看到的範圍 ≈ 拍到的範圍。 */
    fun previewSelector(aspect: Int): ResolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(aspectStrategy(aspect))
        .build()

    /** 拍照用最高解析度，讓小 QR 有足夠像素。 */
    fun captureSelector(aspect: Int): ResolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(aspectStrategy(aspect))
        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
        .build()

    /**
     * 依該鏡頭最大 JPEG 尺寸決定長寬比，避免用到較小的解析度
     * （例如 Nuwa AIR-H300 最大是 3840×2160 = 16:9，若固定 4:3 會損失像素）。
     */
    fun aspectFor(context: Context, lensFacing: Int): Int = try {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == lensFacing
        }
        val max = id?.let {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)
                ?.maxByOrNull { s -> s.width.toLong() * s.height }
        }
        if (max == null) {
            AspectRatio.RATIO_4_3
        } else {
            val ratio = max.width.toFloat() / max.height
            if (abs(ratio - 16f / 9f) < abs(ratio - 4f / 3f)) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
        }
    } catch (_: Exception) {
        AspectRatio.RATIO_4_3
    }

    fun aspectLabel(aspect: Int) = if (aspect == AspectRatio.RATIO_16_9) "16:9" else "4:3"

    @androidx.annotation.OptIn(ExperimentalLensFacing::class)
    fun lensLabel(facing: Int): String = when (facing) {
        CameraSelector.LENS_FACING_BACK -> "後鏡頭"
        CameraSelector.LENS_FACING_FRONT -> "前鏡頭"
        CameraSelector.LENS_FACING_EXTERNAL -> "外接鏡頭"
        else -> "未知鏡頭"
    }
}

class CameraOption(val label: String, val facing: Int, val selector: CameraSelector)

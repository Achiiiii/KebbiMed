package tw.kebbi.medreminder.scan

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import kotlin.math.max

/**
 * 辨識用圖片的唯一存取點。
 *
 * 個資規則：圖片只能放在 app 私有目錄 `filesDir/images/`，
 * 不寫入 MediaStore／相簿／外部儲存。DB 只存檔案路徑。
 */
class ImageStore(filesDir: File) {

    val dir: File = File(filesDir, "images").apply { mkdirs() }

    fun newImageFile(): File {
        dir.mkdirs()
        return File(dir, "${UUID.randomUUID()}.jpg")
    }

    /** 把（已轉正的）Bitmap 存成私有目錄中的 JPEG。 */
    fun saveBitmap(bitmap: Bitmap, quality: Int = 95): File {
        val file = newImageFile()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        return file
    }

    /**
     * 從相簿（Photo Picker）匯入：複製一份轉正後的 JPEG 到私有目錄。
     * 原始相簿檔案不修改、不保留引用。
     */
    fun importFromUri(resolver: ContentResolver, uri: Uri, maxSide: Int = MAX_IMPORT_SIDE): File {
        val orientation = resolver.openInputStream(uri)?.use { readOrientation(it) }
            ?: ExifInterface.ORIENTATION_NORMAL
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "無法讀取圖片" }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxSide)
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("無法解碼圖片")
        val upright = applyOrientation(scaleDownIfNeeded(decoded, maxSide), orientation)
        return saveBitmap(upright)
    }

    /**
     * 讀取私有目錄中的圖片並依 EXIF 轉正。
     * @param maxSide 長邊上限；null 表示原尺寸（給辨識用）
     */
    fun decodeUpright(file: File, maxSide: Int? = null): Bitmap? {
        if (!file.exists()) return null
        val orientation = file.inputStream().use { readOrientation(it) }
        val opts = BitmapFactory.Options()
        if (maxSide != null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            opts.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxSide)
        }
        val bitmap = BitmapFactory.decodeFile(file.path, opts) ?: return null
        val scaled = if (maxSide != null) scaleDownIfNeeded(bitmap, maxSide) else bitmap
        return applyOrientation(scaled, orientation)
    }

    /** 轉正後的寬高（不解碼像素）。 */
    fun uprightSize(file: File): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val orientation = file.inputStream().use { readOrientation(it) }
        val swap = orientation in setOf(
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE,
        )
        return if (swap) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
    }

    fun delete(file: File): Boolean = file.canonicalPath.startsWith(dir.canonicalPath) && file.delete()

    fun deleteAll(): Int = dir.listFiles()?.count { it.isFile && it.delete() } ?: 0

    fun listImages(): List<File> = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: emptyList()

    fun totalBytes(): Long = listImages().sumOf { it.length() }

    private fun readOrientation(input: InputStream): Int = try {
        ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    companion object {
        const val MAX_IMPORT_SIDE = 4096

        fun sampleSizeFor(width: Int, height: Int, maxSide: Int): Int {
            var sample = 1
            var longSide = max(width, height)
            while (longSide / 2 >= maxSide) {
                sample *= 2
                longSide /= 2
            }
            return sample
        }

        fun scaleDownIfNeeded(bitmap: Bitmap, maxSide: Int): Bitmap {
            val longSide = max(bitmap.width, bitmap.height)
            if (longSide <= maxSide) return bitmap
            val ratio = maxSide.toFloat() / longSide
            return Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true,
            )
        }

        fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
                else -> return bitmap
            }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }

        fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
            if (degrees % 360 == 0) return bitmap
            val m = Matrix().apply { setRotate(degrees.toFloat()) }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }
    }
}

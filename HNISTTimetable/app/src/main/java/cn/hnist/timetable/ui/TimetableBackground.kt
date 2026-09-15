package cn.hnist.timetable.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File

/** 课表背景图：存储在应用私有目录，解码时按需缩小，避免大图占爆内存。 */
object TimetableBackground {

    private fun file(context: Context): File = File(context.filesDir, "timetable_bg.jpg")

    fun exists(context: Context): Boolean = file(context).length() > 0

    /**
     * 把用户选的图片按目标宽高比居中裁剪并压缩保存（约 720×1600），
     * 返回一个可以直接给 Bitmap 解码用的低分辨率采样，避免 OOM。
     */
    fun saveFrom(context: Context, uri: Uri): Boolean {
        return try {
            val source = decodeScaled(context, uri, 1440) ?: return false
            val targetRatio = 1600f / 720f
            val w = source.width
            val h = source.height
            val cropped = if (h.toFloat() / w.toFloat() > targetRatio) {
                // 太高：保宽度，垂直方向取中间段
                val targetH = (w * targetRatio).toInt()
                Bitmap.createBitmap(source, 0, (h - targetH) / 2, w, targetH)
            } else {
                // 太宽：保高度，水平方向取中间段
                val targetW = (h / targetRatio).toInt()
                Bitmap.createBitmap(source, (w - targetW) / 2, 0, targetW, h)
            }
            file(context).outputStream().use { out ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            if (cropped !== source) cropped.recycle()
            source.recycle()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 解码出适合铺在课表下面的位图（再降一档分辨率）。 */
    fun loadBitmap(context: Context): Bitmap? {
        if (!exists(context)) return null
        return try {
            decodeFile(file(context), 720)
        } catch (e: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    /** 读取 EXIF 旋转后，把图缩到最长边不超过 [maxSide]。 */
    private fun decodeScaled(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        return try {
            val rotation = readRotation(context, uri)
            // 先量尺寸。注意：inJustDecodeBounds 时 decodeStream 按设计返回 null，
            // 不能把它当成“打开失败”
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsStream = context.contentResolver.openInputStream(uri) ?: return null
            boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decodedStream = context.contentResolver.openInputStream(uri) ?: return null
            val decoded = decodedStream.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            if (rotation != 0f) {
                val m = Matrix().apply { postRotate(rotation) }
                val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
                if (rotated != decoded) decoded.recycle()
                rotated
            } else decoded
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeFile(f: File, maxSide: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        null
    }

    private fun readRotation(context: Context, uri: Uri): Float = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
    } catch (e: Exception) {
        0f
    }
}

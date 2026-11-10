package app.reshebnik.solver

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Область обрезки в долях сторон снимка: (0, 0) — левый верхний угол, (1, 1) — правый нижний.
 */
data class CropArea(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object {
        val FULL = CropArea(0f, 0f, 1f, 1f)
    }
}

object ImagePrep {
    /**
     * Длинная сторона снимка для отправки. Модель принимает до 2576 px; 2048 хватает,
     * чтобы читался мелкий почерк, и снимок стоит меньше токенов.
     */
    private const val MAX_SIDE = 2048

    private const val MIN_CROP_PX = 32

    /** Читает фото, уменьшает его до [MAX_SIDE] и поворачивает как снято (по EXIF). */
    @Throws(IOException::class)
    fun load(resolver: ContentResolver, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.open(uri) { BitmapFactory.decodeStream(it, null, bounds) }
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (longSide <= 0) throw IOException("Not an image: $uri")

        // Грубо уменьшаем при чтении, чтобы снимок с камеры на 50 Мп не занял всю память.
        var sample = 1
        while (longSide / (sample * 2) >= MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.open(uri) { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Cannot decode: $uri")

        val orientation = resolver.open(uri) {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        val matrix = Matrix()
        val scale = MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        if (scale < 1f) matrix.postScale(scale, scale)
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        }
        if (matrix.isIdentity) return decoded
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }

    fun toJpeg(bitmap: Bitmap): ByteArray {
        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, bytes)
        return bytes.toByteArray()
    }

    fun toBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    /** Вырезает область снимка. Слишком маленькая область расширяется до [MIN_CROP_PX]. */
    fun crop(bitmap: Bitmap, area: CropArea): Bitmap {
        if (area == CropArea.FULL) return bitmap
        val left = (area.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val top = (area.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val width = ((area.right - area.left) * bitmap.width).toInt().coerceIn(minOf(MIN_CROP_PX, bitmap.width - left), bitmap.width - left)
        val height = ((area.bottom - area.top) * bitmap.height).toInt().coerceIn(minOf(MIN_CROP_PX, bitmap.height - top), bitmap.height - top)
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    /** Поворот на четверть оборота по часовой стрелке. */
    fun rotate(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Читает снимок из файла уменьшенным: для списка истории и экрана решения. */
    fun loadFile(file: File, maxSide: Int): Bitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (longSide <= 0) return null
        var sample = 1
        while (longSide / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private inline fun <T> ContentResolver.open(uri: Uri, block: (java.io.InputStream) -> T): T =
        (openInputStream(uri) ?: throw IOException("Cannot open: $uri")).use(block)
}

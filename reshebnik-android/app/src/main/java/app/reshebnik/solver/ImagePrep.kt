package app.reshebnik.solver

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.IOException

object ImagePrep {
    /**
     * Длинная сторона снимка для отправки. Модель принимает до 2576 px; 2048 хватает,
     * чтобы читался мелкий почерк, и снимок стоит меньше токенов.
     */
    private const val MAX_SIDE = 2048

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

    fun toJpegBase64(bitmap: Bitmap): String {
        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, bytes)
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }

    private inline fun <T> ContentResolver.open(uri: Uri, block: (java.io.InputStream) -> T): T =
        (openInputStream(uri) ?: throw IOException("Cannot open: $uri")).use(block)
}

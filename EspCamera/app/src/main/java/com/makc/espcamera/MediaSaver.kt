package com.makc.espcamera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Recorder
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves photos to Pictures/ESP Camera and videos to Movies/ESP Camera. */
object MediaSaver {
    private const val FOLDER = "ESP Camera"

    /** Android 9 and older need the storage permission to write to the gallery. */
    fun needsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

    /** Blocking: call off the main thread. */
    fun savePhoto(context: Context, bitmap: Bitmap): Boolean = try {
        val name = "ESP_${stamp()}.jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore refused the photo")
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                ?: error("No stream for $uri")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val file = File(legacyDir(Environment.DIRECTORY_PICTURES), name)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("image/jpeg"), null)
        }
        true
    } catch (e: Exception) {
        android.util.Log.e("EspCamera", "Photo not saved", e)
        false
    }

    fun prepareVideo(context: Context, recorder: Recorder): PendingRecording {
        val name = "ESP_${stamp()}.mp4"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
            }
            val options = MediaStoreOutputOptions
                .Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(values)
                .build()
            recorder.prepareRecording(context, options)
        } else {
            val file = File(legacyDir(Environment.DIRECTORY_MOVIES), name)
            recorder.prepareRecording(context, FileOutputOptions.Builder(file).build())
        }
    }

    /** Old Androids only index files the media scanner was told about. */
    fun scanLegacyVideo(context: Context, path: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && path != null) {
            MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("video/mp4"), null)
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyDir(type: String): File =
        File(Environment.getExternalStoragePublicDirectory(type), FOLDER).apply { mkdirs() }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}

package com.hinnka.mycamera.video

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VideoMediaStoreWriter {

    private const val TAG = "VideoMediaStoreWriter"

    data class PendingVideo(
        val uri: Uri,
        val descriptor: ParcelFileDescriptor
    )

    fun createPendingVideo(
        context: Context,
        dateTakenMs: Long = System.currentTimeMillis()
    ): PendingVideo? {
        val fileName = buildFileName(dateTakenMs)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/PhotonCamera")
            put(MediaStore.MediaColumns.DATE_ADDED, dateTakenMs / 1000)
            put(MediaStore.MediaColumns.DATE_MODIFIED, dateTakenMs / 1000)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        return try {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("Cannot open output descriptor")
            PendingVideo(uri, descriptor)
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to create pending video output", e)
            context.contentResolver.delete(uri, null, null)
            null
        }
    }

    fun publishPendingVideo(context: Context, uri: Uri): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            }
            context.contentResolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to publish pending video", e)
            null
        }
    }

    fun discardPendingVideo(context: Context, uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            PLog.w(TAG, "Failed to discard pending video: ${e.message}")
        }
    }

    suspend fun publishVideo(
        context: Context,
        sourceFile: File,
        dateTakenMs: Long = System.currentTimeMillis()
    ): Uri? = withContext(Dispatchers.IO) {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) {
            return@withContext null
        }

        val fileName = buildFileName(dateTakenMs)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/PhotonCamera")
            put(MediaStore.MediaColumns.DATE_ADDED, dateTakenMs / 1000)
            put(MediaStore.MediaColumns.DATE_MODIFIED, dateTakenMs / 1000)
        }

        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext null

        try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: return@withContext null
            uri
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to publish video", e)
            context.contentResolver.delete(uri, null, null)
            null
        }
    }

    private fun buildFileName(dateTakenMs: Long): String {
        return "PhotonCamera_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(dateTakenMs))
        }.mp4"
    }
}

package com.remotedroid.app.capture

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.OutputStream

/**
 * Receives a file pushed from the browser and writes it into the phone's public
 * Downloads folder. One transfer at a time: file_begin opens a stream, binary
 * chunks are appended, file_end finalizes it.
 */
class FileReceiver(private val ctx: Context) {

    private var out: OutputStream? = null
    private var pendingUri: android.net.Uri? = null
    private var legacyFile: File? = null
    private var received = 0L
    private var expected = 0L
    var currentName: String? = null
        private set

    /** @return true if the transfer started. */
    fun begin(name: String, size: Long): Boolean {
        abort() // clear any half-finished transfer
        val safe = name.substringAfterLast('/').ifBlank { "upload.bin" }
        currentName = safe
        expected = size
        received = 0L
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safe)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return false
                pendingUri = uri
                out = ctx.contentResolver.openOutputStream(uri)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                dir.mkdirs()
                val f = File(dir, safe)
                legacyFile = f
                out = f.outputStream()
            }
            out != null
        } catch (e: Exception) {
            Log.w(TAG, "begin failed", e); abort(); false
        }
    }

    fun chunk(data: ByteArray) {
        try { out?.write(data); received += data.size }
        catch (e: Exception) { Log.w(TAG, "chunk failed", e); abort() }
    }

    /** @return the finished file name, or null on failure. */
    fun end(): String? {
        return try {
            out?.flush(); out?.close(); out = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                pendingUri?.let {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    ctx.contentResolver.update(it, values, null, null)
                }
            }
            val name = currentName
            Log.i(TAG, "received $name ($received/$expected bytes)")
            reset()
            name
        } catch (e: Exception) {
            Log.w(TAG, "end failed", e); abort(); null
        }
    }

    fun abort() {
        try { out?.close() } catch (_: Exception) {}
        try {
            pendingUri?.let { ctx.contentResolver.delete(it, null, null) }
            legacyFile?.delete()
        } catch (_: Exception) {}
        reset()
    }

    private fun reset() {
        out = null; pendingUri = null; legacyFile = null
        received = 0; expected = 0; currentName = null
    }

    companion object { private const val TAG = "FileReceiver" }
}

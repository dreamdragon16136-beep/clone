package com.imperialreader.ui.reader

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.View
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

object GalleryPageSaver {
    const val WRITE_PERMISSION = Manifest.permission.WRITE_EXTERNAL_STORAGE

    fun needsLegacyPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    fun hasLegacyPermission(context: Context): Boolean =
        !needsLegacyPermission() ||
            ContextCompat.checkSelfPermission(context, WRITE_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun captureDisplayedPage(activity: Activity, view: View, onCaptured: (Bitmap?) -> Unit) {
        val visible = Rect()
        if (!view.getLocalVisibleRect(visible)) {
            onCaptured(null)
            return
        }
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val sourceRect = Rect(
            location[0] + visible.left,
            location[1] + visible.top,
            location[0] + visible.right,
            location[1] + visible.bottom
        )
        if (!sourceRect.intersect(0, 0, activity.window.decorView.width, activity.window.decorView.height)) {
            onCaptured(null)
            return
        }
        val bitmap = Bitmap.createBitmap(sourceRect.width(), sourceRect.height(), Bitmap.Config.ARGB_8888)
        PixelCopy.request(activity.window, sourceRect, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) onCaptured(bitmap)
            else {
                bitmap.recycle()
                onCaptured(null)
            }
        }, Handler(Looper.getMainLooper()))
    }

    suspend fun save(context: Context, source: Bitmap): String = withContext(Dispatchers.IO) {
        if (!hasLegacyPermission(context)) throw IOException("Storage permission is needed to save pages")
        val name = "ImperialReader-${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ImperialReader")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Gallery could not create an image entry")
            try {
                val output = resolver.openOutputStream(uri, "w")
                    ?: throw IOException("Gallery image output is unavailable")
                output.use {
                    if (!source.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                        throw IOException("Could not encode the selected page")
                    }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri.toString()
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val directory = File(pictures, "ImperialReader")
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Could not create the gallery folder")
            }
            val file = File(directory, name)
            try {
                file.outputStream().use {
                    if (!source.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                        throw IOException("Could not encode the selected page")
                    }
                }
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DATA, file.absolutePath)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                }
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Gallery could not register the saved page")
                file.absolutePath
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }
    }
}

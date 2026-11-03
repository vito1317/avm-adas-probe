package com.vito.avmprobe

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 存檔到「下載/avm-adas」（Android 10+ 走 MediaStore，免權限）；更舊的系統存到 App 專屬資料夾。
 * 回傳給使用者看的路徑。
 */
object MediaSaver {
    private const val DIR = "avm-adas"

    fun save(context: Context, name: String, mime: String, bytes: ByteArray): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cr = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DIR")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore 建檔失敗")
            cr.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("MediaStore 開檔失敗")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            cr.update(uri, values, null, null)
            return "下載/$DIR/$name"
        }
        val dir = File(context.getExternalFilesDir(null), DIR).apply { mkdirs() }
        val f = File(dir, name)
        f.writeBytes(bytes)
        return f.absolutePath
    }
}

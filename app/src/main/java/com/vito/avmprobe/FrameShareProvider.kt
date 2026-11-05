package com.vito.avmprobe

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * B 模式開關的查詢窗口。原廠 360 注入的 FrameTap 用
 * `ContentResolver.call(content://com.vito.avmprobe.frameshare, "enabled")` 問「要不要共用影格」。
 *
 * 以前是寫旗標檔到 /sdcard/avm-adas/，但 Android 10+ 沒儲存權限寫不進去（而且是靜默失敗），
 * 開關按了等於沒按。改成直接問我們的 App，不經過檔案。
 *
 * 只回一個布林值，沒有其他資料可讀寫，所以 exported 沒有外洩疑慮。
 */
class FrameShareProvider : ContentProvider() {

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_ENABLED) return null
        val ctx = context ?: return null
        return Bundle().apply { putBoolean(KEY_ENABLED, AppPrefs(ctx).frameShare) }
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.vito.avmprobe.frameshare"
        const val METHOD_ENABLED = "enabled"
        const val KEY_ENABLED = "enabled"
    }
}

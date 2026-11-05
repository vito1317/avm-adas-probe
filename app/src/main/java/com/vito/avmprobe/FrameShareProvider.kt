package com.vito.avmprobe

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * B 模式跟原廠 360（注入的 FrameTap）溝通的窗口，全部用同步的 `ContentResolver.call`：
 *
 *  - `enabled` → 開關狀態（以前寫 /sdcard 旗標檔，我們沒儲存權限寫不進去，開了等於沒開）。
 *  - `surface` → 我們 ImageReader 的 Surface。原廠建相機 session 的**當下**同步拿到，
 *    不用等 bindService（以前要等綁定，原廠早就建好 session，影格永遠進不來）。順便把
 *    OverlayService 叫起來跑偵測。
 *  - `log` → FrameTap 回報它做了什麼，記進 [FrameShareHub]，在 3D 面板與 App 主頁顯示。
 *
 * 只回開關、一個 Surface、收一行文字，不提供其他資料，所以 exported 沒有外洩疑慮。
 */
class FrameShareProvider : ContentProvider() {

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        return when (method) {
            METHOD_ENABLED -> Bundle().apply { putBoolean(KEY_ENABLED, AppPrefs(ctx).frameShare) }
            METHOD_SURFACE -> {
                if (!AppPrefs(ctx).frameShare) return null
                val s = FrameShareHub.surface() ?: return null
                try {
                    ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
                } catch (t: Throwable) {
                    FrameShareHub.log("叫起 OverlayService 失敗：$t")
                }
                Bundle().apply { putParcelable(KEY_SURFACE, s) }
            }
            METHOD_LOG -> { FrameShareHub.log("[360] ${arg ?: ""}"); null }
            else -> null
        }
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.vito.avmprobe.frameshare"
        const val METHOD_ENABLED = "enabled"
        const val METHOD_SURFACE = "surface"
        const val METHOD_LOG = "log"
        const val KEY_ENABLED = "enabled"
        const val KEY_SURFACE = "surface"
    }
}

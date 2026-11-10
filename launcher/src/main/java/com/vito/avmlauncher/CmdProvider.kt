package com.vito.avmlauncher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import org.json.JSONArray

/**
 * 中控台 → 360 引擎的「待辦指令」（車窗、車鎖、氛圍燈、看板…）。
 * 廣播任何 App 都能送，所以車控指令不放在廣播裡：中控台把指令排在這裡，只廣播「有新指令」；
 * 360 引擎收到後先確認這個 provider 的擁有者是 com.vito.avmlauncher、而且簽章是固定的那把金鑰，才來 call("take") 拿走。
 */
class CmdProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.vito.avmlauncher.cmd"
        private val queue = ArrayList<String>()
        private const val MAX = 20

        /** 排一個指令（JSON 字串 {"cmd":..,"arg":..}）。 */
        fun push(json: String) = synchronized(queue) { queue.add(json); while (queue.size > MAX) queue.removeAt(0) }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "take") return null
        val a = JSONArray()
        synchronized(queue) { for (s in queue) a.put(s); queue.clear() }
        return Bundle().apply { putString("cmds", a.toString()) }
    }

    override fun onCreate() = true
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

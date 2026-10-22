package com.vito.avmprobe

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.File

/**
 * 探測「能不能繞過截螢幕、直接拿 raw frame」：
 *  - 列出 USB 裝置與介面 class（class 14 = UVC Video → 可用 USB Host 免 root 直讀）
 *  - 列出 /dev/video*（能讀 = v4l2 可行，通常要 root）
 */
object UsbProbe {

    private const val USB_CLASS_VIDEO = 14  // UVC

    fun report(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("=== USB 裝置 ===")
        val um = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = um.deviceList
        if (devices.isEmpty()) {
            sb.appendLine("（沒偵測到 USB 裝置 —— 採集盒可能走內部匯流排，非外接 USB）")
        } else {
            var uvcFound = false
            for ((_, dev) in devices) {
                val isUvc = describeDevice(sb, dev)
                if (isUvc) uvcFound = true
            }
            sb.appendLine()
            sb.appendLine(
                if (uvcFound) "✅ 發現 UVC(class14) 裝置 → 可走 USB Host 免 root 直讀！"
                else "⚠️ 沒看到 UVC class 介面 → 可能是私有協議或走內部匯流排"
            )
        }

        sb.appendLine()
        sb.appendLine("=== /dev/video* ===")
        val vids = File("/dev").listFiles { f -> f.name.startsWith("video") }
            ?.sortedBy { it.name }
        if (vids.isNullOrEmpty()) {
            sb.appendLine("（列不到，通常因權限/SELinux —— v4l2 幾乎確定要 root）")
        } else {
            for (v in vids) {
                sb.appendLine("${v.name}  可讀=${v.canRead()}  可寫=${v.canWrite()}")
            }
            sb.appendLine("→ 若有 node 但不可讀，代表要 root 才能走 v4l2")
        }
        return sb.toString()
    }

    /** 回傳是否為 UVC 裝置 */
    private fun describeDevice(sb: StringBuilder, dev: UsbDevice): Boolean {
        val name = dev.productName ?: "?"
        val mfr = dev.manufacturerName ?: "?"
        sb.appendLine(
            "• %s / %s  VID=%04x PID=%04x".format(
                mfr, name, dev.vendorId, dev.productId
            )
        )
        var isUvc = false
        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            val cls = intf.interfaceClass
            val tag = when (cls) {
                USB_CLASS_VIDEO -> "VIDEO/UVC ★"
                UsbConstants.USB_CLASS_AUDIO -> "AUDIO"
                UsbConstants.USB_CLASS_HID -> "HID"
                UsbConstants.USB_CLASS_MASS_STORAGE -> "STORAGE"
                UsbConstants.USB_CLASS_COMM -> "COMM"
                UsbConstants.USB_CLASS_VENDOR_SPEC -> "VENDOR_SPEC(私有)"
                else -> "class=$cls"
            }
            sb.appendLine("    intf[$i] $tag")
            if (cls == USB_CLASS_VIDEO) isUvc = true
        }
        return isUvc
    }
}

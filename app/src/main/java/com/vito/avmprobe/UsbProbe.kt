package com.vito.avmprobe

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.File

/**
 * 探測「能不能繞過截螢幕、直接拿 raw frame」：
 *  - Camera2：AHD 解碼晶片走 MIPI-CSI 的鏡頭，通常透過標準相機 HAL 公開 → 免 root 直開
 *  - USB：class 14 = UVC Video → 可用 USB Host 免 root 直讀
 *  - /dev/video*：能讀 = v4l2 可行（通常要 root）
 */
object UsbProbe {

    private const val USB_CLASS_VIDEO = 14  // UVC

    fun report(context: Context): String {
        val sb = StringBuilder()
        camera2(context, sb)
        sb.appendLine()
        usb(context, sb)
        sb.appendLine()
        devVideo(sb)
        return sb.toString()
    }

    private fun camera2(context: Context, sb: StringBuilder) {
        sb.appendLine("=== Camera2 鏡頭（MIPI-CSI 鏡頭通常在這）===")
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = try {
            cm.cameraIdList.toList()
        } catch (t: Throwable) {
            sb.appendLine("讀取失敗：${t.message}")
            return
        }
        if (ids.isEmpty()) {
            sb.appendLine("（系統沒公開任何 Camera2 鏡頭 → 全景360 可能走廠商私有介面）")
            return
        }
        for (id in ids) {
            try {
                val c = cm.getCameraCharacteristics(id)
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "前"
                    CameraCharacteristics.LENS_FACING_BACK -> "後"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "外接"
                    else -> "?"
                }
                val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                    else -> "?"
                }
                val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(SurfaceTexture::class.java)
                    ?.sortedByDescending { it.width * it.height }
                    ?.take(4)
                    ?.joinToString { "${it.width}x${it.height}" } ?: "?"
                sb.appendLine("• 鏡頭 $id  朝向=$facing  等級=$level")
                sb.appendLine("    輸出：$sizes")
            } catch (t: Throwable) {
                sb.appendLine("• 鏡頭 $id 讀取失敗：${t.message}")
            }
        }
        sb.appendLine("✅ 有 Camera2 鏡頭 → 按「鏡頭直開測試」實際開開看")
    }

    private fun usb(context: Context, sb: StringBuilder) {
        sb.appendLine("=== USB 裝置 ===")
        val um = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = um.deviceList
        if (devices.isEmpty()) {
            sb.appendLine("（沒偵測到 USB 裝置）")
            return
        }
        var uvcFound = false
        for ((_, dev) in devices) {
            if (describeDevice(sb, dev)) uvcFound = true
        }
        sb.appendLine(
            if (uvcFound) "✅ 發現 UVC(class14) 裝置 → 可走 USB Host 免 root 直讀"
            else "⚠️ USB 上沒有鏡頭 → 360 鏡頭不是走 USB（多半是 AHD 解碼晶片走 MIPI-CSI）"
        )
    }

    private fun devVideo(sb: StringBuilder) {
        sb.appendLine("=== /dev/video* ===")
        val vids = File("/dev").listFiles { f -> f.name.startsWith("video") }?.sortedBy { it.name }
        if (vids.isNullOrEmpty()) {
            sb.appendLine("（列不到，通常因權限/SELinux —— v4l2 幾乎確定要 root）")
        } else {
            for (v in vids) sb.appendLine("${v.name}  可讀=${v.canRead()}  可寫=${v.canWrite()}")
            sb.appendLine("→ 有 node 但不可讀 = 要 root 才能走 v4l2")
        }
    }

    /** 回傳是否為 UVC 裝置 */
    private fun describeDevice(sb: StringBuilder, dev: UsbDevice): Boolean {
        sb.appendLine(
            "• %s / %s  VID=%04x PID=%04x".format(
                dev.manufacturerName ?: "?", dev.productName ?: "?", dev.vendorId, dev.productId
            )
        )
        var isUvc = false
        for (i in 0 until dev.interfaceCount) {
            val cls = dev.getInterface(i).interfaceClass
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

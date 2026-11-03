package com.vito.avmprobe

import android.app.Application

/**
 * 開機就接上車機訊號來源，兩種模式（鏡頭直讀／截螢幕）都讀得到。
 * 來源本身接不上（車機沒有那個服務、廣播沒在送）也不會有事，只是對應欄位一直是 null。
 */
class AvmApp : Application() {

    override fun onCreate() {
        super.onCreate()
        headUnit = HeadUnitSignalAdapter(this)
        VehicleBus.signals.attach(headUnit!!)
    }

    companion object {
        /** 給訊號探測頁看現況用 */
        @JvmStatic
        var headUnit: HeadUnitSignalAdapter? = null
            private set
    }
}

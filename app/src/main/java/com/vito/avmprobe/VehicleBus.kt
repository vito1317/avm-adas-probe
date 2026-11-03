package com.vito.avmprobe

/**
 * 全 App 共用的車身訊號（方向燈、檔位、方向盤、車速、雷達）。
 * 讀取管道（CAN 解碼盒經車機廠商服務/廣播）等「車機訊號探測」結果出來後，在這裡 attach 對應的 adapter。
 */
object VehicleBus {
    val signals = VehicleSignals()
}

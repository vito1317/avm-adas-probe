# 合併版引擎：程式碼會被塞進原廠 360 的 APK，跟原廠的類別放在同一個 class loader。
# 原廠自己帶了一份精簡過的 Kotlin 標準庫（kotlin.*）—— 我們的類別名稱絕不能跟它撞，
# 所以除了入口與 TFLite（JNI 用類別名稱綁定，不能改名）之外，全部改名搬到 com.vito.z。

# 入口：原廠那邊注入的 FrameTap 用反射呼叫
-keep class com.vito.avmprobe.Engine360 {
    public static *;
}

# TFLite：native 方法用類別／方法名稱綁定，GPU delegate 用反射載入
-keep class org.tensorflow.lite.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

-repackageclasses 'com.vito.z'
-allowaccessmodification
-dontwarn **

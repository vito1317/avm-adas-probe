package com.vito.avmlauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 車機桌面：整個畫面是一個 WebView（web/index.html：3D 車子＋中控台＋道路畫面）。
 * 鏡頭畫面只在原廠 360 程式裡（它獨佔相機）；桌面只拿 ADAS 引擎廣播的偵測結果來畫道路畫面。
 * 打 R、打方向燈時原廠 360 會自己跳到前面，桌面從不主動把自己叫回前面。
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var car: CarData
    private val main = Handler(Looper.getMainLooper())
    private var resumed = false

    // 桌面開著的時候每 2 秒跟引擎說「我在看」→ 引擎在背景也繼續偵測、送道路資料
    private val alive = object : Runnable {
        override fun run() { if (resumed) { car.engineCmd("alive", ""); main.postDelayed(this, 2000) } }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        car = CarData(this).also { it.start() }
        web = WebView(this).apply {
            setBackgroundColor(0xFF05070B.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            @Suppress("DEPRECATION") settings.allowFileAccessFromFileURLs = true   // 讀 assets 裡的 .glb
            @Suppress("DEPRECATION") settings.allowUniversalAccessFromFileURLs = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            addJavascriptInterface(Bridge(), "Car")
            loadUrl("file:///android_asset/web/index.html")
        }
        setContentView(web)
        hideBars()
    }

    override fun onResume() { super.onResume(); resumed = true; hideBars(); main.removeCallbacks(alive); main.post(alive); web.onResume() }
    override fun onPause() { resumed = false; web.onPause(); super.onPause() }
    override fun onDestroy() { car.stop(); web.destroy(); super.onDestroy() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideBars() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { web.evaluateJavascript("window.onBack && window.onBack()", null) }   // 桌面不關閉

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun start(i: Intent?): Boolean = try {
        if (i == null) false else { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
    } catch (_: Throwable) { false }

    inner class Bridge {
        private val prefs = getSharedPreferences("launcher", Context.MODE_PRIVATE)

        @JavascriptInterface fun state(): String = car.json()
        @JavascriptInterface fun apps(): String = AppList.json(this@MainActivity)
        @JavascriptInterface fun icon(pkg: String): String = AppList.icon(this@MainActivity, pkg)
        @JavascriptInterface fun launch(pkg: String): Boolean = start(packageManager.getLaunchIntentForPackage(pkg))

        /** 常用入口：360、導航、音樂、電話、設定、藍牙。 */
        @JavascriptInterface fun open(kind: String): Boolean = when (kind) {
            "360" -> start(packageManager.getLaunchIntentForPackage(CarData.ENGINE_PKG))
            "nav" -> start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))) || start(packageManager.getLaunchIntentForPackage("com.google.android.apps.maps"))
            "music" -> start(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))
            "phone" -> start(Intent(Intent.ACTION_DIAL))
            "settings" -> start(Intent(Settings.ACTION_SETTINGS))
            "bt" -> start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            "trip" -> start(Intent(Intent.ACTION_MAIN).setClassName("com.ts.MainUI", "com.ts.main.bmw.DrivingComputeActivity"))
            else -> false
        }

        /** 媒體鍵：play／next／prev。 */
        @JavascriptInterface fun media(action: String) {
            val code = when (action) { "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE }
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }

        @JavascriptInterface fun volume(dir: Int) {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (dir > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        }

        /** 送給 ADAS 引擎（氛圍燈、後擋看板）。 */
        @JavascriptInterface fun engine(cmd: String, arg: String) { if (cmd == "ambient" || cmd == "rear") car.engineCmd(cmd, arg) }

        /**
         * 道路畫面卡片：打開原廠 360，叫引擎切到 Tesla 式道路畫面。
         * 360 可能還沒啟動（引擎還沒收得到廣播）→ 開了之後 1.5 秒、4 秒、8 秒各再送一次（畫面還沒建好時引擎會先記著）。
         */
        @JavascriptInterface fun roadView(): Boolean {
            car.engineCmd("roadview", "")
            val ok = start(packageManager.getLaunchIntentForPackage(CarData.ENGINE_PKG))
            for (d in longArrayOf(1500, 4000, 8000)) main.postDelayed({ car.engineCmd("roadview", "") }, d)
            return ok
        }

        /**
         * 車控（車窗、車鎖、尾箱、空調、氛圍燈、看板）：排進 CmdProvider，再通知 360 引擎來拿。
         * 引擎會先確認 provider 的擁有者與簽章，別的 App 送廣播沒用。
         */
        @JavascriptInterface fun control(cmd: String, arg: String) {
            CmdProvider.push(org.json.JSONObject().put("cmd", cmd).put("arg", arg).toString())
            car.engineCmd("poll", "")
        }

        @JavascriptInterface fun pref(k: String): String = prefs.getString(k, "") ?: ""
        @JavascriptInterface fun setPref(k: String, v: String) { prefs.edit().putString(k, v).apply() }
    }
}

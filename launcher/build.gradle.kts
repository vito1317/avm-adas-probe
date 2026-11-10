import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 車機桌面：3D 車子（WebView + three.js）＋中控台。跟 app 用同一把簽章（keystore.properties，不進版控）。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.vito.avmlauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vito.avmlauncher"
        minSdk = 26
        targetSdk = 29          // 車機是 Android 10；照 Q 的行為（不用處理 Android 11+ 的套件可見性）
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("fixed") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("fixed") ?: signingConfigs.getByName("debug")
        }
        debug { signingConfigs.findByName("fixed")?.let { signingConfig = it } }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
    androidResources { noCompress += "glb" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.11.0")
}

// 3D 道路畫面直接用 app 的 3D 面板（bev3d：three.js、道路使用者模型、E60），建置時複製進來（不在版控放兩份）。
// index.html 注入兩件事：收中控台用 postMessage 送來的每幀偵測結果；藏起它自己的按鈕與面板（中控台有自己的儀表）。
val bev3dOut = layout.buildDirectory.dir("generated/bev3dAssets")
val copyBev3d by tasks.registering(Copy::class) {
    from(rootProject.file("app/src/main/assets/bev3d")) { exclude("map-demo.json", "*-demo.html", "models.html") }
    into(bev3dOut.map { it.dir("web/bev3d") })
    inputs.file(project.buildFile)          // 改了注入內容也要重新複製
    outputs.upToDateWhen { false }
    filesMatching("index.html") {
        filter { line ->
            // 寬螢幕時它會把右邊讓給地圖；中控台右邊是自己的卡片 → 3D 佔滿整個寬度
            line.replace("var wide = w / h > WIDE_AR;", "var wide = false;")
                // 加一個「車頭朝自己的斜視」視角（預設），並讓中控台可以切換視角
                .replace("chase: { pos: [0, 7.5, 10.5], tgt: [0, 0, -1.5], rot: false },",
                    "front: { pos: [-6.8, 3.6, -10], tgt: [0, 0.6, 2], rot: false }, chase: { pos: [0, 7.5, 10.5], tgt: [0, 0, -1.5], rot: false },")
                .replace("function setView(name) {", "window.__setView = function (n) { autoView = null; userView = n; setView(n); }; function setView(name) {")
                .replace("var curView = 'chase', userView = 'chase', autoView = null;", "var curView = 'front', userView = 'front', autoView = null;")
                .replace("setView('chase');", "setView('front');")
                .replace("ego.add(car);", "ego.add(car); window.__egoCar = car;")      // 給 carfx.js 加開門／車燈／方向燈／煞車特效
                .replace("var col = s.o ? slotCol.used : slotCol.free;", "var col = s.own ? srgb(0x28e1ff) : s.o ? slotCol.used : slotCol.free;")   // 自車停的那格：青色
                .replace("</body>",
                "<style>.panel,.dock,.topbtn,.cal,#stat,.tl{display:none!important}</style>" +
                "<script>window.addEventListener('message',function(e){try{var d=e.data;if(!d)return;if(d.__view){if(window.__setView)window.__setView(d.__view);return;}if(window.setFrame)window.setFrame(d)}catch(x){}});" +
                // 預設是「實景」地面（要鏡頭畫面），中控台沒有 → 換成 Tesla 式的虛擬道路、追車視角
                "window.addEventListener('load',function(){var b=document.querySelector('#dock button[data-ground=virtual]');" +
                "if(b&&b.getAttribute('aria-pressed')!=='true')b.click();});</script><script src=\"../carfx.js\"></script></body>")
        }
    }
}
android.sourceSets["main"].assets.srcDir(bev3dOut)
tasks.named("preBuild") { dependsOn(copyBev3d) }

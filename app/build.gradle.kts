import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 固定簽章：金鑰與密碼放在專案根目錄的 keystore.properties（不進版控）。
// 每版都用同一把 key 簽，車機才能直接覆蓋安裝，不用先解除安裝。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.vito.avmprobe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vito.avmprobe"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "0.3.7"
    }

    buildFeatures {
        aidl = true   // com.ts.tsspeechlib.car.ITsSpeechCar（車機車身訊號服務）
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("fixed") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        } else {
            logger.warn("找不到 keystore.properties：改用本機 debug key，裝到車機上可能要先解除安裝舊版")
        }
    }

    buildTypes {
        val fixed = signingConfigs.findByName("fixed")
        debug {
            if (fixed != null) signingConfig = fixed
        }
        release {
            if (fixed != null) signingConfig = fixed
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // release 建置不跑 lint 檢查（它要另外下載 IntelliJ 元件，網路不穩時會讓建置失敗）
    lint {
        checkReleaseBuilds = false
    }

    // tflite 模型不要被壓縮，才能用 mmap 直接載入
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.11.0")   // WebViewAssetLoader：3D 面板讀 assets

    // TFLite / LiteRT + GPU delegate（GPU 失敗會自動退回 CPU/XNNPACK）
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.16.1")

    // UVC 直讀（免 root，內含預編 native lib）。若 mavenCentral 解析失敗，
    // 改用 jitpack 座標：com.github.herohan:UVCAndroid:<tag>
    implementation("com.herohan:UVCAndroid:1.0.4")

    // 純 Kotlin 演算法（幾何、道路、三角錐、紅綠燈）的 JVM 單元測試
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // android.jar 的 org.json 在 JVM 測試裡是空殼
}

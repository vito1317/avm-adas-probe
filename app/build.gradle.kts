plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vito.avmprobe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vito.avmprobe"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
    }

    buildTypes {
        release {
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
}

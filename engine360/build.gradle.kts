plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 合併版引擎：直接編譯 app 模組的原始碼（同一份偵測管線），用 R8 只留 Engine360 用得到的部分，
// 並把所有類別（含 Kotlin 標準庫、AndroidX）改名到 com.vito.z，避免跟原廠 360 內建的撞名。
// 產出的 APK 不安裝，只取 dex 與 TFLite 的 .so 塞進原廠 APK（見 merge360/build-b.sh）。
android {
    namespace = "com.vito.avmprobe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vito.avm360engine"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1"
        ndk { abiFilters += "armeabi-v7a" }   // 原廠 360 是 32 位元
    }

    buildFeatures { aidl = true }

    sourceSets["main"].apply {
        java.srcDirs("../app/src/main/java")
        aidl.srcDirs("../app/src/main/aidl")
        res.srcDirs("../app/src/main/res")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "engine-rules.pro")
            signingConfig = signingConfigs.getByName("debug")   // 只是為了產生 APK，不會被安裝
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false }
}

// androidx.media 會把 android.support.v4.media.* 以原名保留（Parcelable 相容），
// 原廠 360 的舊 support library 裡也有同名類別。引擎用不到它，直接排除。
configurations.all { exclude(group = "androidx.media") }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.16.1")
    implementation("com.herohan:UVCAndroid:1.0.4")
}

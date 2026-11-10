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

// 3D 車子展示用 app 的 bev3d 裡的 three.js 與 E60 模型（已轉成公尺、車頭朝 +Z、車燈材質有命名），建置時複製進來
val bev3dOut = layout.buildDirectory.dir("generated/bev3dAssets")
val copyBev3d by tasks.registering(Copy::class) {
    from(rootProject.file("app/src/main/assets/bev3d")) { include("lib/**", "models/e60.glb", "models/CREDITS.md") }
    into(bev3dOut.map { it.dir("web/bev3d") })
}
android.sourceSets["main"].assets.srcDir(bev3dOut)
tasks.named("preBuild") { dependsOn(copyBev3d) }

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 手機 App：跟車機（中控台）互聯——設定導航目的地、看車況、把手機 GPS 給車機用。跟車機用同一把簽章（keystore.properties，不進版控）。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.vito.avmphone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vito.avmphone"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "1.8"
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // 遠端 AI：跟 relay 的 WebSocket
}

// 360 環景頁用中控台同一套 3D 道路畫面（bev3d：three.js、E60 與周圍車輛／行人模型），
// 直接拿中控台建置時產生的那份（已注入接收資料、藏起自帶按鈕），加上車身特效 carfx.js；不在版控放第三份。
val phoneWebOut = layout.buildDirectory.dir("generated/phoneWeb")
val copyCarFx by tasks.registering(Copy::class) {
    from(rootProject.file("launcher/src/main/assets/web/carfx.js"))
    into(phoneWebOut.map { it.dir("web") })
}
android.sourceSets["main"].assets.srcDir(phoneWebOut)
android.sourceSets["main"].assets.srcDir(project(":launcher").layout.buildDirectory.dir("generated/bev3dAssets"))
tasks.named("preBuild") { dependsOn(copyCarFx, ":launcher:copyBev3d") }

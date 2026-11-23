plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "tw.kebbi.medreminder"
    compileSdk = 35

    defaultConfig {
        applicationId = "tw.kebbi.medreminder"
        minSdk = 29          // D30：目標裝置只有凱比 AIR-H300（Android 15）
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        // 後端網址：空的就用假後端。第三方後端完成後在這裡填，例如 "https://example.com/api/v1/"
        buildConfigField("String", "API_BASE_URL", "\"\"")
        // 凱比是 arm64-v8a，模擬器是 x86_64；其他架構不打包，APK 小很多（ML Kit 中文 OCR 模型很大）
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // JVM 單元測試裡呼叫到 android.util.Log 之類的方法時回傳預設值，不要直接失敗
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":api"))

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // 本機資料庫（occurrence、outbox）
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 背景同步（每 15 分鐘心跳、有網路時補送事件）
    implementation(libs.androidx.work.runtime.ktx)

    // 連後端（第三方後端完成後才會用到；在那之前用假後端）
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)

    // 掃藥袋：CameraX + ML Kit（bundled，凱比沒有 Google Play 服務）
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.mlkit.vision)
    implementation(libs.androidx.exifinterface)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.guava) // 相機用到的 ListenableFuture

    // 配對碼的 QR 圖
    implementation(libs.zxing.core)

    // 凱比原廠 SDK（Nuwa）：aar 不放公開 repo，開發者自己從 https://dss.nuwarobotics.com/ 下載放進 app/libs/（D4、CR-37）
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation(libs.gson) // Nuwa SDK 需要

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

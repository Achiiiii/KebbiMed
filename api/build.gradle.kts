import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 純 Kotlin 模組，不依賴 Android：凱比會呼叫的後端 API 都定義在這裡。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.retrofit)
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

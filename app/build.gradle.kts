import java.io.File
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val gitCommitId = try {
    Runtime.getRuntime().exec("git rev-parse --short HEAD").inputStream.bufferedReader().readText().trim()
} catch (e: Exception) {
    "unknown"
}

android {
    namespace = "com.zeerd.real_timetranscriptionapp"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.zeerd.real_timetranscriptionapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-$gitCommitId"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "**/libonnxruntime.so"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.commons.compress)
    implementation(libs.onnx.android)
    implementation(libs.onnx.ext)
    implementation(libs.sherpa.onnx)
    implementation(libs.litertlm)
    implementation(libs.litert.gpu)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(libs.androidx.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// 1. 注册一个自定义的下载任务
val downloadAssets = tasks.register("downloadAssets") {
    val outputDir = file("src/main/assets/")
    val targetFile = File(outputDir, "silero_vad.onnx")

    outputs.file(targetFile)

    doLast {
        if (!targetFile.exists()) {
            outputDir.mkdirs()
            println("====== 正在下载固定资产文件... ======")
            try {
                URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx").toURL().openStream().use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                println("====== 下载完成！ ======")
            } catch (e: Exception) {
                throw GradleException("资产下载失败，请检查网络连接: ${e.message}")
            }
        } else {
            println("====== 资产文件已存在，跳过下载 ======")
        }
    }
}

// 2. 挂载到 Android 编译生命周期，在预编译阶段执行
tasks.named("preBuild") {
    dependsOn(downloadAssets)
}

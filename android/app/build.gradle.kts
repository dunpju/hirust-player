plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hirust.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hirust.player"
        minSdk = 24
        targetSdk = 35

        // 版本自动化:CI 传 -PVERSION_NAME=v1.2.3 时按 tag 计算,否则用手动默认值。
        val tagVersion = (findProperty("VERSION_NAME") as String?)?.removePrefix("v")
        versionName = tagVersion ?: "0.7.0"
        versionCode = tagVersion
            ?.split('.')
            ?.take(3)
            ?.map { it.toIntOrNull() ?: 0 }
            ?.let { (it.getOrElse(0) { 0 }) * 10000 + (it.getOrElse(1) { 0 }) * 100 + (it.getOrElse(2) { 0 }) }
            ?: 7

        // 与 rust/Cargo.toml 的 Android 目标保持一致(模拟器为 x86_64)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            // CI 由 release.yml 注入以下环境变量;本地缺省时不配置(回退 debug 签名)。
            val ksFile = System.getenv("HIRUST_KEYSTORE_FILE")
            if (ksFile != null) {
                storeFile = file(ksFile)
                storePassword = System.getenv("HIRUST_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HIRUST_KEY_ALIAS")
                keyPassword = System.getenv("HIRUST_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 压缩/混淆(keep 规则见 proguard-rules.pro)
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (System.getenv("HIRUST_KEYSTORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                // 无密钥环境(本地)退化为 debug 签名,保证产物可安装。
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
}

/**
 * 交叉编译 Rust cdylib 到 jniLibs(生成物不入库)。
 * 需要:cargo、cargo-ndk、ANDROID_NDK_HOME;详见 CLAUDE.md §6/§7。
 */
val buildRust by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir.parentFile.resolve("rust")
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
        "-o", projectDir.resolve("src/main/jniLibs").absolutePath,
        "build", "--release", "-p", "player-jni",
    )
}

tasks.named("preBuild") {
    dependsOn(buildRust)
}

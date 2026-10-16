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
        versionCode = 1
        versionName = "0.1.0"

        // 与 rust/Cargo.toml 的 Android 目标保持一致(模拟器为 x86_64)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            // M6 再启用 R8(需 keep AudioBridge)
            isMinifyEnabled = false
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

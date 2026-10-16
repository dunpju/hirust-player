# HiRust Player

Android 本地音频播放器:**Rust 核心**(symphonia 解码 + oboe 低延迟输出),Jetpack Compose UI,GitHub Actions 构建。

> 开发计划、架构与规范见 [CLAUDE.md](CLAUDE.md)。当前进度:**M0 骨架完成**。

## 目录

```
├─ CLAUDE.md                  # 架构、选型、M0–M6 开发计划、规范
├─ .github/workflows/ci.yml   # CI
├─ rust/                      # Rust workspace(player-core 引擎 / player-jni JNI 桥)
└─ android/                   # Gradle 工程(Compose 壳)
```

## CI

push 到 `main` 或提 PR 自动触发:

- **rust-check**:fmt / clippy / 单元测试
- **android-build**:交叉编译 Rust(arm64-v8a / armeabi-v7a / x86_64)→ Gradle 打 debug APK

APK 下载:**Actions → 对应运行 → Artifacts → `hirust-player-debug-apk`**。

## 本地构建

前置(详见 CLAUDE.md §6):JDK 17、Android SDK 35、NDK r26+(`ANDROID_NDK_HOME`)、Rust stable + 3 个 Android target、cargo-ndk。

```bash
# 1) Rust 质检 + 交叉编译(gradle preBuild 也会自动执行 cargo ndk)
cd rust
cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 \
  -o ../android/app/src/main/jniLibs build --release -p player-jni

# 2) APK
cd ../android
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 推送到 GitHub

```bash
git remote add origin <你的仓库地址>
git push -u origin main
```

推送后 Actions 自动出首个 APK。

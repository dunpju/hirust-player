# HiRust Player

Android 本地音频播放器:**Rust 核心**(symphonia 解码 + AudioTrack 输出,引擎/队列/状态机全在 Rust),Jetpack Compose UI,GitHub Actions 构建与签名发布。

> 开发计划、架构与规范见 [CLAUDE.md](CLAUDE.md)。当前进度:**M0–M6 代码全部完成**(M2–M6 待真机验收)。

## 支持格式

mp3 / flac / ogg-vorbis / wav / aac-m4a(另含 alac、adpcm 等 symphonia 内建编解码)。opus/ape/wavpack 暂不支持(见 CLAUDE.md R3)。

## 目录

```
├─ CLAUDE.md                          # 架构、选型、里程碑、规范
├─ .github/workflows/ci.yml           # CI:rust-check + debug APK
├─ .github/workflows/release.yml      # 推 tag v* → 签名 release APK → GitHub Releases
├─ rust/                              # Rust workspace(player-core 引擎 / player-jni JNI 桥)
└─ android/                           # Gradle 工程(Compose UI + Media3 会话)
```

## CI / 发布

push 到 `main` 或提 PR 自动触发 CI:fmt / clippy / 测试 + 交叉编译三 ABI 打 debug APK
(**Actions → 对应运行 → Artifacts → `hirust-player-debug-apk`**)。

### 发 Release(一次性配置签名)

1. 本地生成 keystore(保管好,丢失无法续签升级):

   ```bash
   keytool -genkey -v -keystore hirust.keystore -alias hirust \
     -keyalg RSA -keysize 2048 -validity 10000
   ```

2. 仓库 **Settings → Secrets and variables → Actions** 添加四个 Secret:

   | Secret | 值 |
   |---|---|
   | `HIRUST_KEYSTORE_B64` | `base64 -w0 hirust.keystore` 的输出(Git Bash: `base64 -w 0`) |
   | `HIRUST_KEYSTORE_PASSWORD` | keystore 密码 |
   | `HIRUST_KEY_ALIAS` | 别名(如 `hirust`) |
   | `HIRUST_KEY_PASSWORD` | 密钥密码(与库密码相同可省) |

3. 打 tag 推送:

   ```bash
   git tag v0.6.0 && git push origin v0.6.0
   ```

   Release 工作流自动:解密签名 → R8 → `hirust-player-v0.6.0.apk` 附到 GitHub Releases。
   未配置 Secrets 时同样出包,但退化为 debug 签名(不可覆盖安装正式版)。

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

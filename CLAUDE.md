# CLAUDE.md — HiRust Player 项目指南

> 本文档是所有开发会话(人与 Claude)的唯一事实来源:架构、选型、计划、规范都在这里。
> 若与代码冲突,以代码为准,并回改本文档。

## 1. 项目使命

Android 本地音频播放器:**Rust 承担全部播放核心**(解码、引擎、输出),Kotlin/Jetpack Compose 只做 UI 与系统集成(通知、权限、文件访问)。目标是低延迟、格式覆盖广、可长期维护的原生播放器。

- 应用名:HiRust Player
- 包名:`com.hirust.player`
- 最低支持:Android 7.0(API 24);target/compile:API 35

## 2. 里程碑看板

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 骨架 + CI(可安装 APK,JNI 打通) | ✅ 2026-10-16 |
| M1 | Rust 引擎核心(解码/队列/状态机,桌面可测) | ✅ 2026-10-16 |
| M2 | Android 输出 + 完整 JNI 桥 + 最小播放页 | 🔶 代码完成,待真机验收 |
| M3 | 播放列表 + 后台播放(MediaSession) | ⬜ |
| M4 | 元数据/封面/seek/播放模式 | ⬜ |
| M5 | 主题/设置/焦点与耳机体验打磨 | ⬜ |
| M6 | 签名发布 + GitHub Releases | ⬜ |

## 3. 架构总览

```
Android App (Kotlin)
├─ Compose UI(播放页/队列/设置) ← ViewModel: StateFlow<UiState>
└─ 系统集成:MediaSession(通知/锁屏)、音频焦点、SAF/MediaStore 扫描
        │  JNI ── com.hirust.player.audio.AudioBridge ↔ libplayer_jni.so
        ▼
Rust 核心
├─ player-jni:JNI 翻译层(类型转换/异常映射,无业务逻辑)
└─ player-core:引擎核心(纯 Rust,桌面可测)
   ├─ 解码:symphonia(mp3/flac/ogg/wav/aac)
   ├─ 播放队列 + 状态机 + seek + 音量
   └─ 输出抽象 AudioOutput:
      ├─ Android 后端:oboe(AAudio,API<26 自动回退 OpenSL ES)〔M2〕
      └─ 测试后端:MockOutput(模拟时钟)〔M1〕
```

分层职责:

- **UI 层(Compose)**:纯展示与交互,状态来自 ViewModel 的 `StateFlow<UiState>`,不直接触 JNI。
- **系统集成层(Kotlin)**:MediaSession 通知/锁屏、音频焦点、SAF/MediaStore 文件访问、权限。
- **player-jni(Rust cdylib)**:JNI 翻译层。只做类型转换与异常映射,无业务逻辑。
- **player-core(Rust)**:引擎核心。解码、播放队列、状态机、seek、音量;**不依赖任何 Android 类型**。

线程模型(性能红线):

- **音频回调线程**(oboe 高优先级):只从环形缓冲读 PCM 写入输出。禁止堆分配、阻塞锁、日志、JNI。
- **解码线程**:pull 解码器 → 填充环形缓冲;处理 seek 与曲目切换。
- **控制线程**:Kotlin 经 JNI 发命令(经通道传递,毫秒级返回)。
- **事件回调**:引擎线程 attach JVM 调 `AudioBridge` 回调;位置更新节流 ~10Hz。

## 4. 技术选型与决策记录

| # | 领域 | 选择 | 备选 | 理由 |
|---|---|---|---|---|
| D1 | 解码 | symphonia | ffmpeg 绑定 | 纯 Rust、许可干净;mp3/flac/ogg/wav/aac 开箱即用 |
| D2 | 音频输出 | **AudioTrack via JNI**(M2 起)→ 后续评估 oboe-rs/aaudio | oboe-rs、aaudio crate | oboe-rs 上游停更、与新 NDK 兼容风险高;AudioTrack 零额外原生依赖、阻塞写自带背压,音乐播放延迟足够。trait 抽象可替换(见 R1) |
| D3 | Kotlin↔Rust | 手写 `jni` crate | UniFFI | API 面小、Rust→Kotlin 回调语义直接;API 膨胀再评估 |
| D4 | UI | Compose + Material 3 | XML Views | 新项目默认;与 StateFlow 单向数据流契合 |
| D5 | 后台/通知 | Media3 MediaSession(仅控制面) | 自建通知 | 统一锁屏/耳机/车载控制;播放仍由 Rust 引擎驱动 |
| D6 | Rust 交叉构建 | cargo-ndk | rust-android-gradle 插件 | 不引入第三方 maven;CI 与本地命令一致 |
| D7 | CI | GitHub Actions ubuntu runner | — | 免费额度足够;产物走 artifact/release |

## 5. 目录结构

```
hirust-player/
├─ CLAUDE.md                     # 本文档
├─ README.md
├─ .github/workflows/ci.yml      # CI(rust-check + android-build)
├─ rust/                         # Rust workspace
│  ├─ Cargo.toml
│  ├─ player-core/               # 引擎核心(无 Android 依赖)
│  │  ├─ src/{lib,engine,decoder,queue,output,error}.rs
│  │  └─ tests/{common,decoder,engine}.rs   # WAV 程序化生成,无二进制 fixtures
│  └─ player-jni/                # cdylib JNI 桥 → libplayer_jni.so
│     └─ src/{lib,output,events}.rs   # 命令 / AudioTrack 宿主 / 事件泵
└─ android/
   ├─ settings.gradle.kts / build.gradle.kts / gradle.properties
   └─ app/
      ├─ build.gradle.kts        # preBuild 自动执行 buildRust(cargo ndk)
      └─ src/main/
         ├─ AndroidManifest.xml
         ├─ java/com/hirust/player/
         │  ├─ MainActivity.kt        # Compose 播放页
         │  ├─ PlayerViewModel.kt     # UiState/焦点/SAF 落盘
         │  └─ audio/AudioBridge.kt   # JNI 唯一入口 + AudioTrack 宿主
         └─ jniLibs/             # 生成物,不入库
```

## 6. 环境要求(本地开发)

| 工具 | 版本 | 说明 |
|---|---|---|
| Rust | stable ≥ 1.82 | `rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android` |
| cargo-ndk | ≥ 3.5 | `cargo install cargo-ndk` |
| Android SDK | Platform 35 | licenses 需接受 |
| Android NDK | r26+ | 设置 `ANDROID_NDK_HOME`;Windows 下路径勿含空格/中文 |
| JDK | 17 | Gradle 与 AGP 要求 |
| Gradle | ≥ 8.9 | 仓库未含 wrapper;可在 android/ 下 `gradle wrapper --gradle-version 8.10.2` 生成,或直接用 Android Studio |

CI 不依赖本地环境:runner 自行安装上述工具链。模拟器使用 x86_64 ABI(已包含在构建目标中)。

## 7. 常用命令

(在仓库根目录,Git Bash)

```bash
# Rust 质量门(改 Rust 后必跑)
cd rust
cargo fmt --all
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace

# 交叉编译 Rust → android/app/src/main/jniLibs(gradle preBuild 也会自动执行)
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 \
  -o ../android/app/src/main/jniLibs build --release -p player-jni

# 构建/安装 APK
cd ../android
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 日志(M2 接入 android_logger 后 tag=hirust)
adb logcat -s hirust
```

## 8. JNI 桥接契约

- 动态库:`libplayer_jni.so`(rust/player-jni,crate-type=cdylib)
- Kotlin 唯一入口:`com.hirust.player.audio.AudioBridge`(object)
- Rust 函数命名:`Java_com_hirust_player_audio_AudioBridge_<方法名>`
- 错误映射:Rust `Err` → `JNIEnv::throw_new` → Kotlin 侧不得静默吞异常

方法表(新增必须同步两侧代码并更新此表):

| 方法 | 方向 | 状态 |
|---|---|---|
| `version(): String` | K→R | ✅ M0 |
| `engineStart()` / `release()` | K→R | ✅ M2 |
| `setQueue(paths, start)` / `play()` / `pause()` / `resume()` / `stop()` / `next()` / `prev()` | K→R | ✅ M2 |
| `seekMs(ms)` / `positionMs()` / `durationMs()` / `state()` | K↔R | ✅ M2 |
| `setRepeatMode(mode)` / `setShuffle(on)` | K→R | ✅ M2 |
| `onStateChanged` / `onPositionChanged` / `onTrackStarted` / `onTrackEnded` / `onQueueEnded` / `onError` | R→K 回调 | ✅ M2 |
| `outputStart/Write/Suspend/Resume/Stop`(AudioTrack 宿主) | R→K | ✅ M2 |
| `setVolume(v)` | K→R | M4 |

## 9. 开发计划(里程碑明细)

### M0 骨架与 CI ✅(2026-10-16)

- [x] 仓库结构、CLAUDE.md 计划落地
- [x] Rust workspace:player-core(状态机占位)+ player-jni(version JNI)
- [x] Android 壳工程:Compose 页面显示 Rust 引擎版本(验证 .so 加载)
- [x] GitHub Actions:rust-check + android-build 产出 debug APK artifact
- 验收:CI 绿;APK 安装后显示「Rust 引擎版本:0.1.0」

### M1 Rust 引擎核心(player-core,桌面可测)✅(2026-10-16)

- [x] `AudioOutput` trait 定稿 + MockOutput(可选实时节奏)+ SampleSink 断言句柄
- [x] symphonia 解码:probe、逐块交织 f32、时长、seek(时间戳自校准位置)
- [x] Engine:命令通道 + 引擎线程 + 状态机 + 事件流(位置 10Hz 节流)
- [x] 播放队列:循环(关/单曲/列表)+ 随机序(当前曲置首)+ 手动跳转
- [x] 测试:tests/{decoder,engine}.rs + WAV 程序化生成,共 16 用例
- 验收:`cargo test --workspace` 全绿;player-core 仅依赖 symphonia/thiserror,无 Android 依赖 ✅

### M2 Android 输出与完整 JNI 桥 🔶(2026-10-16 代码完成,待真机验收)

- [x] 输出后端:`JniOutput`(AudioTrack via JNI,见 D2 修订;oboe/AAudio 后续评估)
- [x] AudioBridge 完整命令 API + Rust→Kotlin 事件回调(位置 10Hz 节流,事件泵线程)
- [x] android_logger(tag=hirust)+ panic hook,崩溃日志可读
- [x] 最小播放页:SAF 多选文件(复制到私有缓存)、播放/暂停/切歌、进度拖动
- [x] 音频焦点最简实现(永久丢失即暂停,API 26+ 用 AudioFocusRequest)
- [ ] 验收:真机播 mp3/flac 无爆音;后台短时不断音(CI 出包后装机验证)

### M3 播放列表与后台播放

- [ ] MediaStore 扫描 + 播放列表 UI(正在播放指示)
- [ ] Media3 MediaSessionService:通知/锁屏控制、耳机按键
- [ ] 前台服务与进程被杀后队列恢复
- 验收:锁屏可控制;后台连播 ≥30 分钟无中断

### M4 元数据与播放体验

- [ ] ID3v2/VorbisComment/FLAC 标签 + 内嵌封面(磁盘缓存)
- [ ] SeekBar 拖动 seek、循环/随机切换、音量
- [ ] (可选)ReplayGain、淡入淡出
- 验收:seek 误差 <200ms;列表显示标题/艺人/封面

### M5 体验打磨

- [ ] 深色主题 + Material You 动态取色
- [ ] 设置页(输出缓冲、扫描目录、语言)
- [ ] 耳机拔出暂停、焦点 ducking、蓝牙设备切换
- 验收:monkey 测试 1 小时无崩溃

### M6 发布

- [ ] release.yml:签名(keystore 走 GitHub Secrets)→ GitHub Releases 附 APK
- [ ] R8 混淆(keep AudioBridge)、版本号自动化
- [ ] 扩展格式评估(ape/wavpack/opus)
- 验收:推 tag 自动出 Release

## 10. CI(GitHub Actions)

`.github/workflows/ci.yml`,触发:push 到 main / PR / 手动(workflow_dispatch)。

| Job | 内容 |
|---|---|
| rust-check | `cargo fmt --check` + `clippy -D warnings` + `test`(ubuntu 宿主) |
| android-build | 安装稳定版 NDK(26–28 系列)→ cargo-ndk 编译 3 个 ABI → `gradle :app:assembleDebug` → 上传 artifact `hirust-player-debug-apk` |

注意:

- `jniLibs/` 是 CI/本地生成物,不入库;
- 仓库未提交 gradle wrapper,CI 用 `gradle/actions/setup-gradle` 指定 8.10.2;
- `Cargo.lock` 不入库(见 .gitignore);CI 每次按 semver 解析最新兼容版本。

## 11. 编码规范

**Rust**

- `cargo fmt` 默认风格;clippy 全量 `-D warnings`
- 错误用 thiserror 逐层传播;运行时路径禁 `unwrap/expect`(测试与编译期常量除外)
- 音频回调内禁:堆分配、阻塞锁、日志、JNI
- 公共 API 带文档注释;引擎与 JNI 逻辑严格分离

**Kotlin**

- 官方风格;Compose 状态上提,Composable 无副作用
- UI 字符串进 `strings.xml`(zh 默认,后续 i18n)
- 不在 UI 线程做阻塞 JNI 调用(位置轮询除外,且须节流)

**提交与版本**

- Conventional Commits:`feat:` / `fix:` / `docs:` / `chore:` …
- 版本三处同步:rust workspace `version`、app `versionName`、§2 看板

## 12. 已知风险与预案

| # | 风险 | 预案 |
|---|---|---|
| R1 | oboe-rs 上游维护放缓 | `AudioOutput` trait 隔离;可换 aaudio crate 或 AudioTrack JNI 桥 |
| R2 | API 24 无 AAudio(需 26) | oboe 自动回退 OpenSL;若受阻将 minSdk 提到 26(需决策) |
| R3 | symphonia 不支持 ape/wavpack | M6 评估 symphonia feature 或 ffmpeg sidecar |
| R4 | 新 NDK 与 cargo-ndk 兼容性 | CI 只选 26–28 系列;必要时锁定精确版本 |
| R5 | Media3 与自研引擎状态同步 | 只用 MediaSession 控制面,状态以引擎为准单向同步 |

## 13. 工作守则(Claude 每次会话必读)

1. 改 Rust 后必跑 §7 三连;改 Kotlin 后至少 `assembleDebug`;保持 CI 绿。
2. `jniLibs/`、`target/`、`build/`、`.idea/` 永不入库。
3. 完成条目后勾选 §9,并更新 §2 看板与日期。
4. 新增 JNI 方法 → 两侧代码 + §8 契约表同步更新。
5. 架构/选型变更 → 先改 §4 决策记录再写代码。
6. 版本发布 → 同步三处版本号(§11)。

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
| M2 | Android 输出 + 完整 JNI 桥 + 最小播放页 | ✅ 2026-10-16 真机验收通过 |
| M3 | 播放列表 + 后台播放(MediaSession) | ✅ 2026-10-16 真机验收通过 |
| M4 | 元数据/封面/seek/播放模式 | ✅ 2026-10-16 真机验收通过 |
| M5 | 主题/设置/焦点与耳机体验打磨 | ✅ 2026-10-16 真机验收通过 |
| M6 | 签名发布 + GitHub Releases | ✅ 2026-10-20 首个 Release v0.8.0 已发布 |

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
| D1 | 解码 | symphonia(features: mp3/aac/isomp4/opt-simd,显式开启) | ffmpeg 绑定 | 纯 Rust、许可干净;**默认 feature 不含 mp3/aac,必须显式开启**(M6 实测发现);opus/ape/wavpack 无支持(见 R3) |
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
│  │  ├─ src/{lib,engine,decoder,queue,output,error,metadata}.rs
│  │  └─ tests/{common,decoder,engine,metadata}.rs   # WAV 程序化生成,无二进制 fixtures
│  └─ player-jni/                # cdylib JNI 桥 → libplayer_jni.so
│     └─ src/{lib,output,events}.rs   # 命令 / AudioTrack 宿主 / 事件泵
└─ android/
   ├─ settings.gradle.kts / build.gradle.kts / gradle.properties
   └─ app/
      ├─ build.gradle.kts        # preBuild 自动执行 buildRust(cargo ndk)
      └─ src/main/
         ├─ AndroidManifest.xml
         ├─ java/com/hirust/player/
         │  ├─ MainActivity.kt        # Compose:曲库列表 + 播放控制
         │  ├─ PlayerViewModel.kt     # MediaStore 扫描 + 命令透传
         │  ├─ PlaybackService.kt     # MediaSessionService + 焦点 + 事件分发
         │  ├─ EnginePlayer.kt        # SimpleBasePlayer 包装 AudioBridge
         │  ├─ PlayerBus.kt           # 全局状态总线 + 队列持久化/恢复 + ducking
         │  ├─ Settings.kt            # 应用设置(缓冲/短曲阈值)
         │  ├─ ui/Theme.kt            # 深浅色 + Material You 动态取色
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
| `queueIndex()` | K↔R | ✅ 0.7.0(回前台自愈) |
| `setRepeatMode(mode)` / `setShuffle(on)` | K→R | ✅ M2 |
| `onStateChanged` / `onPositionChanged` / `onTrackStarted` / `onTrackEnded` / `onQueueEnded` / `onError` | R→K 回调 | ✅ M2 |
| `outputStart/Write/Suspend/Resume/Stop`(AudioTrack 宿主) | R→K | ✅ M2 |
| `setVolume(v)` / `trackInfo(path)` / `trackCover(path)` | K↔R | ✅ M4 |
| `setSpeed(v)` / `outputSetSpeed`(AudioTrack 宿主) | K↔R | ✅ 0.8.0 |

## 9. 开发计划(里程碑明细)

### M0 骨架与 CI ✅(2026-10-16)

- [x] 仓库结构、CLAUDE.md 计划落地
- [x] Rust workspace:player-core(状态机占位)+ player-jni(version JNI)
- [x] Android 壳工程:Compose 页面显示 Rust 引擎版本(验证 .so 加载)
- [x] GitHub Actions:rust-check + android-build 产出 debug APK artifact
- 验收:CI 绿;APK 安装后显示「Rust 引擎版本:0.1.0」
- ⚠️ 修正(2026-10-16 M6 后复查):M0–M6 期间 **CI 实际全部失败**(android-build 的 `setup-android@v3` step 持续报错,APK 从未产出;rust-check 的 test 在 Linux 慢速 runner 上因固定 sleep 断言不稳)。当日已修复:移除 setup-android 改用镜像自带 sdkmanager;时序断言改为条件等待。此前"CI 绿"的表述不实,以 Actions 页实际结果为准。**CI 于 `a56a05e` 首次全绿**,首个可下载 APK 产出。

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
- [x] 验收:真机播 mp3/flac 无爆音;后台短时不断音 ✅(2026-10-16 真机通过)

### M3 播放列表与后台播放 🔶(2026-10-16 代码完成,待真机验收)

- [x] MediaStore 扫描(READ_MEDIA_AUDIO/旧存储权限,过滤 <10s)+ LazyColumn 曲目列表(正在播放指示,点击播放)
- [x] Media3 MediaSessionService:通知/锁屏控制、耳机按键(EnginePlayer = SimpleBasePlayer 包装 AudioBridge,仅控制面)
- [x] 前台服务(mediaPlayback 类型,media3 自动前台化);队列按路径持久化,进程被杀后恢复(暂停态)
- [x] 音频焦点移交 PlaybackService;PlayerBus 单一状态源(UI 读 / 服务写)
- [x] 验收:锁屏可控制;后台连播 ≥30 分钟无中断;划掉任务后播放不中断 ✅(2026-10-16 真机通过)
- 备注:恢复不还原播放位置(seek 需先打开解码器);曲目内进度由通知 seekbar 读取 positionMs

### M4 元数据与播放体验 🔶(2026-10-16 代码完成,待真机验收)

- [x] 标签读取:Rust `metadata::read_tags`(symphonia,标准化 std_key + 字符串 key 兜底),JNI `trackInfo(path)` 返回 JSON
- [x] 封面:Rust `read_cover`(优先 FrontCover)→ JNI `trackCover(path)`;Kotlin CoverStore 磁盘缓存;列表/正在播放/通知封面(正在播放=内嵌优先、MediaStore albumart 兜底;列表=albumart)
- [x] SeekBar 拖动 seek(M2 已做,实测节流 10Hz)
- [x] 循环/随机切换 UI(PlayerBus 单一状态源,通知与 UI 双向一致)+ 音量滑条(引擎线程线性缩放,JNI `setVolume`)
- [x] 验收:seek 误差 <200ms;列表显示标题/艺人/封面;音量即时生效 ✅(2026-10-16 真机通过)
- 备注:ReplayGain/淡入淡出继续搁置(M5+ 再评估);恢复场景列表元数据来自上次扫描持久化

### M5 体验打磨 🔶(2026-10-16 代码完成,待真机验收)

- [x] 深色主题(values/values-night 双主题)+ Material You 动态取色(API 31+,ui/Theme.kt)
- [x] 设置:输出缓冲 ms(下一首生效)、忽略短音频阈值(保存即重扫);SharedPreferences(Settings.kt)
- [x] 耳机/蓝牙断开暂停(AUDIO_BECOMING_NOISY 广播)
- [x] 焦点分级:永久丢失暂停 / 瞬时丢失暂停且恢复时续播 / CAN_DUCK 压至 20% 音量(PlayerBus.setDucked,用户音量不受污染)
- [x] 验收:monkey 1 小时无崩溃;耳机拔出即停;通知类 App(导航/助手)播放时正确压低或暂停并恢复 ✅(2026-10-16 真机通过)
- 备注:语言切换(需 appcompat + 全量字符串抽取)与扫描目录白名单推迟到 M6 后;i18n 前先完成 strings.xml 迁移

### M6 发布 🔶(2026-10-16 APK 验收通过;tag 发布待执行)

- [x] release.yml:推 tag `v*` 触发 → 签名(Secrets:HIRUST_KEYSTORE_B64/PASSWORD/ALIAS/KEY_PASSWORD;缺失时自动退 debug 签名)→ `assembleRelease` → GitHub Releases 附 APK(自动生成 release notes)
- [x] R8 压缩混淆启用(proguard-rules.pro:keep AudioBridge/PlayStates/RepeatModes/EngineListener);版本自动化:`-PVERSION_NAME=vX.Y.Z` → versionName/三段 versionCode
- [x] 格式评估(实证):symphonia 0.5.5 无 opus/ape/wavpack;且默认 feature 不含 mp3/aac → 已显式开启 mp3/aac/isomp4/opt-simd(修复 M0 以来的隐性缺口)
- [x] R8 后真机回归(JNI 加载、播放、封面)✅(2026-10-16 真机通过)
- [x] 推 tag `v0.8.0` → Release 工作流全绿,Releases 页发布 `hirust-player-v0.8.0.apk`(2026-10-20;Secrets 已配为正式签名,未配自动退 debug 签名)
- 补充(M6 后):应用图标落地(adaptive icon,26+ 蒙版自适应;24/25 layer-list 兜底;全 XML 无 PNG);CI 修复见 M0 修正记录
- 密钥生成与 Secrets 配置步骤见 README「发布」

### M6 后迭代

#### 文件夹播放列表(0.7.0,2026-10-16)🔶 待真机验收

- 需求:选择手机指定目录下的音频播放;**一个目录 = 一个播放列表**
- [x] SAF `OpenDocumentTree` 选目录 → 解析为真实路径(externalstorage 主/副卷;其他 provider 提示不支持)
- [x] 目录列表 = 文件系统枚举(扩展名过滤、按名排序)+ MediaStore 路径前缀查询合并元数据;未入库文件经 Rust `trackInfo` 补全标题/艺人/时长
- [x] 队列(tracks)与视图(view)分离:切列表不打断播放;`viewSource==queueSource` 才高亮当前曲目(恢复场景用哨兵避免错误高亮)
- [x] 文件夹列表持久化,重启自动恢复;chips 切换「全部音乐 / 各文件夹 / ＋添加」
- [x] 引擎鲁棒性:打开失败的曲目自动跳下一首(原为停掉整队);连续失败达队列长度才结束队列(防循环模式死循环);+2 桌面测试
- [x] 修复:播放页总时长恒显 0:00——`BusState.durationMs` 从未被写入;现于 `onTrackStarted` 时从引擎拉取(引擎先写时长再发事件,时序安全),`onQueueEnded` 归零
- [x] 修复:播放中点选其他曲目无响应(提示「未在播放」)——引擎 `SetQueue` 原本只换队列数据、`play()` 在 Playing 态为 no-op;现播放/暂停中替换队列立即切到新起点曲目并保持播放状态(+1 桌面测试,共 23 用例)
- [x] 修复:窄屏下控制行 5 键溢出,末尾「随机」按钮被挤压成空白椭圆;改为两行布局(播放三键 / 顺序+随机+音量)
- [x] 修复:拖动进度条回弹、时间不跟随——seek 异步空窗期(引擎 10Hz 事件未到)UI 弹回旧位置;现 `PlayerBus.seekTo` 乐观写入目标位置(引擎 seek 处理完即发精确位置事件校正),拖动中时间文本跟随手指;通知栏 seek 同路径统一
- [x] 修复:后台切歌回前台 UI 停在上一首(曲目/时间/高亮全卡)——此前引擎状态只有事件单向推送、无查询通道,丢事件即永久失联。新增 JNI `queueIndex()`(Engine.current_index,Shared 原子变量);`PlayerBus.syncFromEngine()` 拉权威状态;列表自动滚动到正在播放曲目(+1 桌面测试,共 24 用例)
- [x] 修复:回前台后进度卡住不动(仅再切一次后台才更新)——两层:① Rust 事件泵每条事件后检查并清理 pending exception(未清理的异常会毒化线程后续所有 JNI 调用,表现为事件链整体死亡);② 前台期间 2Hz 轮询引擎状态兜底(`startPolling/stopPolling`,ON_RESUME/PAUSE 驱动;§11 规范豁免的节流位置轮询),事件泵正常时仍主导实时性
- [ ] 验收:添加含 mp3/flac 的目录 → 列表正确、元数据完整;播放中切到其他列表不打断;**播放中点选同列表其他曲目立即切换**;混入损坏文件自动跳过;重启后文件夹仍在;播放页总时长与曲目一致;拖动进度无回弹、时间跟随;**后台切歌回前台 UI 正确跟随(标题/时间/高亮/滚动,进度持续前进)**

#### 倍速播放(0.8.0,2026-10-20)🔶 待真机验收

- 需求:0.5/1.0/1.5/2.0 倍速,默认 1.0;按钮在下一曲右侧,播放三键靠左腾位
- [x] 链路:UI 档位循环(PlayerBus.SPEED_STEPS)→ JNI `setSpeed` → 引擎 `Command::SetSpeed`(clamp 0.25–3.0)→ `AudioOutput::set_speed` → Kotlin `outputSetSpeed`(AudioTrack PlaybackParams,平台变速+音调补偿)
- [x] 切曲自动重放速率(输出流按曲新建归 1.0,open_current 非默认速率重放);暂停态设置倍速不触发播放(setPlaybackParams 隐式启动的坑已防护)
- [x] MockOutput 记录速率,SampleSink.last_speed 可断言;+1 桌面测试(生效+切歌重放),共 25 用例
- [ ] 验收:四档即时切换、音调正常;切歌保持倍速;暂停中切倍速不自行播放;位置推进与倍速一致(2.0× 时进度条每秒走 2 秒)

#### 播放记录——重开定位上次目录(0.9.0,2026-10-29)🔶 待真机验收

- 需求:杀进程重开 App,自动定位到**上次播放目录**的列表并高亮上次曲目;目录不存在才默认「全部音乐」
- [x] 持久化队列来源(KEY_SOURCE,空串=全部;随 TrackStarted 的 persist 一并保存)
- [x] 恢复:restoreQueue 写回真实来源(移除 RESTORED 哨兵——视图与来源一致时高亮/滚动到上次曲目,一致前不误高亮);`restoreLastView()` 在权限就绪后打开上次文件夹(等待服务恢复文件夹列表,最多 2s;目录不存在则不动,由 scan 落到全部)
- [x] 时序:restoreLastView 先于 scan(scan 仅在 viewSource==null 时设全部,不覆盖已恢复的文件夹视图)
- [x] 修复:重开后 NowPlaying 显示「未在播放」——引擎 `current_index` 原本仅在解码器打开时写入,恢复态(暂停未播放)查询恒为 -1。现 SetQueue 即写入队列逻辑位置(+1 桌面测试,共 26 用例);restoreQueue 直接写钳制后的持久化下标;移除启动时与 setQueue 竞态的 syncFromEngine(ON_RESUME 轮询兜底)
- [x] 修复:重开后标题正确但视图落在「全部音乐」且无高亮滚动——`restoreLastView` 冷启动时可能先于服务 `restore()` 执行,内存版 `savedSource` 尚为 null 即误退。改为直接读 prefs(`PlayerBus.lastSource(ctx)`),不依赖服务时序;文件夹等待循环保持在读源之后
- [ ] 验收:文件夹列表中播放 → 杀进程重开 → 自动打开该文件夹、高亮滚动到上次曲目、NowPlaying 显示上次队列(暂停态);「全部音乐」播放重开 → 停在全部;删除该文件夹后重开 → 回落到全部

## 10. CI(GitHub Actions)

`.github/workflows/ci.yml`,触发:push 到 main / PR / 手动(workflow_dispatch)。另有 `.github/workflows/release.yml`:推 tag `v*` 触发签名发布。

| Job | 内容 |
|---|---|
| rust-check | `cargo fmt --check` + `clippy -D warnings` + `test`(ubuntu 宿主;时序类测试用条件等待,不依赖墙钟) |
| android-build | 用镜像自带 sdkmanager 安装稳定版 NDK(26–28 系列;**不使用 `setup-android` action**,实测持续失败)→ cargo-ndk 编译 3 个 ABI → `gradle :app:assembleDebug` → 上传 artifact `hirust-player-debug-apk` |

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
| R3 | symphonia 不支持 ape/wavpack | M6 已确认(0.5.5 无对应 feature,opus 同样无);如需支持走 ffmpeg sidecar,列为 M6 后大项 |
| R4 | 新 NDK 与 cargo-ndk 兼容性 | CI 只选 26–28 系列;必要时锁定精确版本 |
| R5 | Media3 与自研引擎状态同步 | 只用 MediaSession 控制面,状态以引擎为准单向同步 |

## 13. 工作守则(Claude 每次会话必读)

1. 改 Rust 后必跑 §7 三连;改 Kotlin 后至少 `assembleDebug`;保持 CI 绿。
2. `jniLibs/`、`target/`、`build/`、`.idea/` 永不入库。
3. 完成条目后勾选 §9,并更新 §2 看板与日期。
4. 新增 JNI 方法 → 两侧代码 + §8 契约表同步更新。
5. 架构/选型变更 → 先改 §4 决策记录再写代码。
6. 版本发布 → 同步三处版本号(§11)。

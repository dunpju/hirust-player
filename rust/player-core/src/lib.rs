//! # player-core
//!
//! HiRust Player 的音频引擎核心:解码、播放队列与状态机。
//!
//! 设计约束(见仓库根 CLAUDE.md):
//! - 本 crate 不得依赖任何 Android/JNI 类型,保证可在桌面宿主上跑单元测试;
//! - 音频输出通过 [`output::AudioOutput`] 抽象,Android 侧由 oboe 后端实现(M2),
//!   测试时用模拟输出;
//! - 音频回调路径上禁止堆分配、阻塞锁与 JNI 调用。

pub mod engine;
pub mod output;

/// 引擎核心版本,取自 Cargo workspace version,由 player-jni 透出给 UI。
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

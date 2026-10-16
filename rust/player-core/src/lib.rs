//! # player-core
//!
//! HiRust Player 的音频引擎核心:解码、播放队列、引擎线程与状态机。
//!
//! 设计约束(见仓库根 CLAUDE.md):
//! - 本 crate 不得依赖任何 Android/JNI 类型,保证可在桌面宿主上跑单元测试;
//! - 音频输出通过 [`AudioOutput`] 抽象,Android 侧由 oboe 后端实现(M2),
//!   测试时用 [`MockOutput`];
//! - 引擎线程允许分配/日志,但音频回调路径(M2 的 oboe 回调)禁止
//!   堆分配、阻塞锁与 JNI。
//!
//! 典型用法:
//! ```no_run
//! use player_core::{Engine, MockOutput, PlayState};
//!
//! let (mock, _sink) = MockOutput::new(true);
//! let engine = Engine::new(Box::new(mock));
//! engine.set_queue(vec!["/music/a.wav".into()], Some(0));
//! engine.play();
//! assert_eq!(engine.state(), PlayState::Playing);
//! engine.shutdown();
//! ```

pub mod decoder;
pub mod engine;
pub mod error;
pub mod metadata;
pub mod output;
pub mod queue;

pub use decoder::{AudioDecoder, MediaInfo};
pub use engine::{Command, Engine, EngineEvent, PlayState};
pub use error::CoreError;
pub use metadata::{read_cover, read_tags, Cover, TrackTags};
pub use output::{AudioOutput, MockOutput, OutputError, SampleSink};
pub use queue::{PlayQueue, QueueItem, RepeatMode};

/// 引擎核心版本,取自 Cargo workspace version,由 player-jni 透出给 UI。
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

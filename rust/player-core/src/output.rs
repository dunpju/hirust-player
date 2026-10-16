//! 音频输出抽象。
//!
//! 所有输出后端(Android 的 oboe/AAudio、测试的模拟输出)实现同一 trait,
//! 引擎只面向 [`AudioOutput`] 编程,便于在桌面宿主跑集成测试。

/// 输出后端错误。
#[derive(Debug, thiserror::Error)]
pub enum OutputError {
    /// 设备不支持请求的采样率/声道布局。
    #[error("输出设备不支持请求的音频格式: {sample_rate}Hz × {channels}ch")]
    UnsupportedFormat {
        /// 请求的采样率。
        sample_rate: u32,
        /// 请求的声道数。
        channels: u8,
    },
    /// 底层流 I/O 故障。
    #[error("音频输出 I/O 错误: {0}")]
    Io(String),
}

/// 音频输出后端接口(M1 随引擎落地逐步细化)。
///
/// 实现方注意:回调式后端(oboe)的音频线程内禁止堆分配、阻塞锁与 JNI。
pub trait AudioOutput: Send {
    /// 以给定格式启动输出流。
    fn start(&mut self, sample_rate: u32, channels: u8) -> Result<(), OutputError>;

    /// 暂时挂起流(可恢复)。
    fn suspend(&mut self) -> Result<(), OutputError>;

    /// 恢复已挂起的流。
    fn resume(&mut self) -> Result<(), OutputError>;

    /// 完全停止并释放流资源。
    fn stop(&mut self) -> Result<(), OutputError>;
}

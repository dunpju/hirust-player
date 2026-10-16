//! 音频输出抽象与测试用模拟输出。

use std::sync::{Arc, Mutex};
use std::time::Duration;

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

/// 音频输出后端接口。
///
/// 引擎以块为单位推送交织(interleaved)f32 PCM;后端内部缓冲,
/// Android 侧由 oboe 回调从内部环形缓冲拉取(M2)。
///
/// 实现方注意:回调式后端(oboe)的音频线程内禁止堆分配、阻塞锁与 JNI。
pub trait AudioOutput: Send {
    /// 以给定格式启动输出流(每个曲目开始时调用)。
    fn start(&mut self, sample_rate: u32, channels: u8) -> Result<(), OutputError>;

    /// 写入一块交织 f32 样本(长度为 channels 的整数倍)。
    fn write(&mut self, block: &[f32]) -> Result<(), OutputError>;

    /// 暂时挂起流(可恢复)。
    fn suspend(&mut self) -> Result<(), OutputError>;

    /// 恢复已挂起的流。
    fn resume(&mut self) -> Result<(), OutputError>;

    /// 完全停止并释放流资源。
    fn stop(&mut self) -> Result<(), OutputError>;
}

/// 模拟输出的采样记录句柄,供测试断言。
#[derive(Clone, Default)]
pub struct SampleSink(Arc<Mutex<Vec<f32>>>);

impl SampleSink {
    /// 已写入的样本总数(所有声道交织计数)。
    pub fn total_samples(&self) -> usize {
        self.0.lock().map(|s| s.len()).unwrap_or(0)
    }

    /// 当前已写入样本的拷贝(测试断言用)。
    pub fn snapshot(&self) -> Vec<f32> {
        self.0.lock().map(|s| s.clone()).unwrap_or_default()
    }
}

/// 测试用模拟输出:记录写入的样本,可按实时速率消费。
///
/// - `paced = true`:每块按样本数对应的真实时长 sleep,模拟真实设备节奏;
/// - `paced = false`:立即消费,解码全速,测试更快。
///
/// 通过 [`MockOutput::new`] 返回的 [`SampleSink`] 观察写入结果。
pub struct MockOutput {
    paced: bool,
    rate: u32,
    channels: u8,
    started: bool,
    suspended: bool,
    samples: Arc<Mutex<Vec<f32>>>,
}

impl MockOutput {
    /// 创建模拟输出及其采样记录句柄。
    pub fn new(paced: bool) -> (Self, SampleSink) {
        let sink = SampleSink::default();
        (
            Self {
                paced,
                rate: 48_000,
                channels: 2,
                started: false,
                suspended: false,
                samples: sink.0.clone(),
            },
            sink,
        )
    }
}

impl AudioOutput for MockOutput {
    fn start(&mut self, sample_rate: u32, channels: u8) -> Result<(), OutputError> {
        self.rate = sample_rate.max(1);
        self.channels = channels.max(1);
        self.started = true;
        self.suspended = false;
        Ok(())
    }

    fn write(&mut self, block: &[f32]) -> Result<(), OutputError> {
        if !self.started {
            return Err(OutputError::Io("流尚未启动".into()));
        }
        if let Ok(mut s) = self.samples.lock() {
            s.extend_from_slice(block);
        }
        if self.paced && !self.suspended {
            let frames = block.len() as u64 / self.channels as u64;
            let ns = frames * 1_000_000_000 / u64::from(self.rate);
            std::thread::sleep(Duration::from_nanos(ns));
        }
        Ok(())
    }

    fn suspend(&mut self) -> Result<(), OutputError> {
        self.suspended = true;
        Ok(())
    }

    fn resume(&mut self) -> Result<(), OutputError> {
        self.suspended = false;
        Ok(())
    }

    fn stop(&mut self) -> Result<(), OutputError> {
        self.started = false;
        Ok(())
    }
}

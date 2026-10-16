//! player-core 统一错误类型。

use crate::output::OutputError;

/// player-core 对外错误。
#[derive(Debug, thiserror::Error)]
pub enum CoreError {
    /// 媒体探测/打开失败(文件不存在、格式不支持、无音频轨等)。
    #[error("媒体打开失败: {0}")]
    Open(String),
    /// 解码或 seek 失败。
    #[error("解码错误: {0}")]
    Decode(String),
    /// 输出后端故障。
    #[error("输出错误: {0}")]
    Output(#[from] OutputError),
    /// 队列操作非法(空队列、索引越界等)。
    #[error("队列错误: {0}")]
    Queue(String),
}

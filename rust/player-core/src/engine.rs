//! 播放引擎状态机。
//!
//! M0 阶段仅承载状态与转换,用于打通 JNI 链路;
//! M1 将接入解码线程、命令通道与输出后端(见 CLAUDE.md 里程碑)。

/// 播放器对外可见的状态。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum PlayState {
    /// 空闲,无曲目加载。
    #[default]
    Stopped,
    /// 正在播放。
    Playing,
    /// 已加载但暂停。
    Paused,
}

/// 引擎占位实现。
#[derive(Debug, Default)]
pub struct Engine {
    state: PlayState,
}

impl Engine {
    pub fn new() -> Self {
        Self::default()
    }

    /// 当前状态。
    pub fn state(&self) -> PlayState {
        self.state
    }

    /// 开始/继续播放。
    pub fn play(&mut self) {
        self.state = PlayState::Playing;
    }

    /// 暂停。
    pub fn pause(&mut self) {
        self.state = PlayState::Paused;
    }

    /// 停止并清空状态。
    pub fn stop(&mut self) {
        self.state = PlayState::Stopped;
    }
}

#[cfg(test)]
mod tests {
    use super::{Engine, PlayState};

    #[test]
    fn state_transitions() {
        let mut engine = Engine::new();
        assert_eq!(engine.state(), PlayState::Stopped);

        engine.play();
        assert_eq!(engine.state(), PlayState::Playing);

        engine.pause();
        assert_eq!(engine.state(), PlayState::Paused);

        engine.play();
        assert_eq!(engine.state(), PlayState::Playing);

        engine.stop();
        assert_eq!(engine.state(), PlayState::Stopped);
    }
}

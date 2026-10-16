//! 播放引擎:命令通道 + 解码线程 + 状态机。
//!
//! 线程模型(CLAUDE.md §3):控制线程经 [`Command`] 通道发指令(毫秒级返回),
//! 引擎线程解码并以块为单位推给 [`AudioOutput`],事件经 [`EngineEvent`] 回传。
//! 音频回调线程由输出后端在 M2 引入,本层只与后端的 `write` 打交道。

use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{Receiver, Sender, TryRecvError};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::decoder::AudioDecoder;
use crate::output::AudioOutput;
use crate::queue::{PlayQueue, QueueItem, RepeatMode};

/// 位置事件节流间隔(~10Hz)。
const POSITION_EMIT_INTERVAL: Duration = Duration::from_millis(100);

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

/// 控制命令(UI/JNI → 引擎线程),全部异步、立即入队。
pub enum Command {
    /// 替换队列并(可选)指定初始曲目索引。
    SetQueue {
        items: Vec<PathBuf>,
        start: Option<usize>,
    },
    /// 播放:暂停中 → 恢复;停止 → 从当前曲目开始。
    Play,
    Pause,
    /// 恢复(仅暂停态有效)。
    Resume,
    /// 停止当前曲目(保留队列与当前索引)。
    Stop,
    /// 用户手动切歌(单曲循环下仍然前进)。
    Next,
    Prev,
    /// 当前曲目内 seek(毫秒)。
    Seek {
        ms: u64,
    },
    SetRepeat(RepeatMode),
    SetShuffle(bool),
    /// 输出音量 [0,1],线性缩放(引擎线程应用,不进音频回调)。
    SetVolume(f32),
    Shutdown,
}

/// 引擎事件(引擎线程 → UI/JNI)。
pub enum EngineEvent {
    StateChanged(PlayState),
    TrackStarted {
        index: usize,
    },
    /// 曲目自然播完(单曲循环下的重复播放同样触发)。
    TrackEnded {
        index: usize,
    },
    /// 队列播放完毕。
    QueueEnded,
    /// 位置变化(节流)。
    PositionChanged {
        ms: u64,
    },
    Error {
        message: String,
    },
}

/// 线程间共享的查询快照(供 Engine 句柄无锁读)。
#[derive(Default)]
struct Shared {
    state: Mutex<PlayState>,
    position_ms: AtomicU64,
    duration_ms: AtomicU64,
}

/// 引擎句柄:发送命令、接收事件、查询状态;drop 时自动关停引擎线程。
pub struct Engine {
    tx: Sender<Command>,
    events: Receiver<EngineEvent>,
    shared: Arc<Shared>,
}

impl Engine {
    /// 以指定输出后端启动引擎线程。
    pub fn new(output: Box<dyn AudioOutput>) -> Self {
        let (tx, rx) = std::sync::mpsc::channel();
        let (etx, erx) = std::sync::mpsc::channel();
        let shared = Arc::new(Shared::default());
        let worker_shared = Arc::clone(&shared);

        let spawned = std::thread::Builder::new()
            .name("hirust-engine".to_string())
            .spawn(move || run(rx, etx, output, worker_shared));
        // 线程创建失败属进程级资源耗尽,按 CLAUDE.md §11 属可 panic 的例外。
        spawned.expect("引擎线程创建失败");

        Self {
            tx,
            events: erx,
            shared,
        }
    }

    /// 发送原始命令。
    pub fn send(&self, cmd: Command) {
        let _ = self.tx.send(cmd);
    }

    /// 替换队列。
    pub fn set_queue(&self, items: Vec<PathBuf>, start: Option<usize>) {
        self.send(Command::SetQueue { items, start });
    }

    pub fn play(&self) {
        self.send(Command::Play);
    }

    pub fn pause(&self) {
        self.send(Command::Pause);
    }

    pub fn resume(&self) {
        self.send(Command::Resume);
    }

    pub fn stop(&self) {
        self.send(Command::Stop);
    }

    pub fn next(&self) {
        self.send(Command::Next);
    }

    pub fn prev(&self) {
        self.send(Command::Prev);
    }

    pub fn seek_ms(&self, ms: u64) {
        self.send(Command::Seek { ms });
    }

    pub fn set_repeat(&self, mode: RepeatMode) {
        self.send(Command::SetRepeat(mode));
    }

    pub fn set_shuffle(&self, on: bool) {
        self.send(Command::SetShuffle(on));
    }

    /// 设置音量 [0,1],超界自动钳制。
    pub fn set_volume(&self, volume: f32) {
        self.send(Command::SetVolume(volume));
    }

    /// 非阻塞取一条事件。
    pub fn try_event(&self) -> Option<EngineEvent> {
        self.events.try_recv().ok()
    }

    /// 当前状态。
    pub fn state(&self) -> PlayState {
        self.shared
            .state
            .lock()
            .map(|s| *s)
            .unwrap_or(PlayState::Stopped)
    }

    /// 当前位置(毫秒)。
    pub fn position_ms(&self) -> u64 {
        self.shared.position_ms.load(Ordering::Relaxed)
    }

    /// 当前曲目总时长(毫秒;未知为 0)。
    pub fn duration_ms(&self) -> u64 {
        self.shared.duration_ms.load(Ordering::Relaxed)
    }

    /// 显式关停引擎线程。
    pub fn shutdown(self) {
        let _ = self.tx.send(Command::Shutdown);
    }

    /// 把事件接收端整体移出(交给独立的事件泵线程阻塞消费)。
    ///
    /// 移出后 [`Engine::try_event`] 恒返回 `None`;查询请用
    /// [`Engine::position_ms`] / [`Engine::state`]。
    pub fn take_events(&mut self) -> Receiver<EngineEvent> {
        let (_, dummy) = std::sync::mpsc::channel();
        std::mem::replace(&mut self.events, dummy)
    }
}

impl Drop for Engine {
    fn drop(&mut self) {
        let _ = self.tx.send(Command::Shutdown);
    }
}

fn run(
    rx: Receiver<Command>,
    etx: Sender<EngineEvent>,
    output: Box<dyn AudioOutput>,
    shared: Arc<Shared>,
) {
    let mut worker = Worker {
        queue: PlayQueue::new(),
        decoder: None,
        output,
        state: PlayState::Stopped,
        last_pos_emit: None,
        volume: 1.0,
        scratch: Vec::new(),
    };

    'outer: loop {
        if worker.is_active() {
            // 播放中:先非阻塞清空命令,再解码一块(输出节流决定整体节奏)。
            loop {
                match rx.try_recv() {
                    Ok(cmd) => {
                        if !worker.handle(cmd, &etx, &shared) {
                            break 'outer;
                        }
                    }
                    Err(TryRecvError::Empty) => break,
                    Err(TryRecvError::Disconnected) => break 'outer,
                }
            }
            worker.decode_step(&etx, &shared);
        } else {
            // 空闲/暂停:阻塞等待命令。
            match rx.recv() {
                Ok(cmd) => {
                    if !worker.handle(cmd, &etx, &shared) {
                        break 'outer;
                    }
                }
                Err(_) => break 'outer,
            }
        }
    }

    // 退出前释放输出资源(AudioTrack 等),不依赖 Drop 顺序。
    let _ = worker.output.stop();
}

/// 引擎线程内部状态。
struct Worker {
    queue: PlayQueue,
    decoder: Option<AudioDecoder>,
    output: Box<dyn AudioOutput>,
    state: PlayState,
    last_pos_emit: Option<Instant>,
    /// 线性音量 [0,1]。
    volume: f32,
    /// 音量缩放复用缓冲(非 1.0 时启用)。
    scratch: Vec<f32>,
}

impl Worker {
    fn is_active(&self) -> bool {
        self.state == PlayState::Playing && self.decoder.is_some()
    }

    /// 处理一条命令;返回 false 表示关停引擎线程。
    fn handle(&mut self, cmd: Command, etx: &Sender<EngineEvent>, shared: &Shared) -> bool {
        match cmd {
            Command::Shutdown => return false,
            Command::SetQueue { items, start } => {
                let items = items.into_iter().map(|path| QueueItem { path }).collect();
                self.queue.set_items(items, start);
            }
            Command::Play => match self.state {
                PlayState::Paused => self.resume_playback(etx, shared),
                PlayState::Stopped => {
                    if self.open_current(true, etx, shared) {
                        self.set_state(PlayState::Playing, etx, shared);
                    }
                }
                PlayState::Playing => {}
            },
            Command::Resume => {
                if self.state == PlayState::Paused {
                    self.resume_playback(etx, shared);
                }
            }
            Command::Pause => {
                if self.state == PlayState::Playing {
                    if let Err(e) = self.output.suspend() {
                        self.emit_error(etx, format!("挂起输出失败: {e}"));
                    }
                    self.set_state(PlayState::Paused, etx, shared);
                }
            }
            Command::Stop => self.stop_current(etx, shared),
            Command::Next => self.switch(true, etx, shared),
            Command::Prev => self.switch(false, etx, shared),
            Command::Seek { ms } => {
                if let Some(dec) = self.decoder.as_mut() {
                    match dec.seek_ms(ms) {
                        Ok(()) => {
                            let pos = dec.position_ms();
                            shared.position_ms.store(pos, Ordering::Relaxed);
                            self.last_pos_emit = Some(Instant::now());
                            let _ = etx.send(EngineEvent::PositionChanged { ms: pos });
                        }
                        Err(e) => self.emit_error(etx, e.to_string()),
                    }
                }
            }
            Command::SetRepeat(mode) => self.queue.set_repeat_mode(mode),
            Command::SetShuffle(on) => self.queue.set_shuffle(on),
            Command::SetVolume(v) => self.volume = v.clamp(0.0, 1.0),
        }
        true
    }

    /// 解码一块并写入输出;处理曲目结束/错误。
    fn decode_step(&mut self, etx: &Sender<EngineEvent>, shared: &Shared) {
        let Some(dec) = self.decoder.as_mut() else {
            return;
        };

        match dec.next_block() {
            Ok(Some(block)) => {
                // 音量缩放(音量为 1.0 时零开销直通)。
                let block: &[f32] = if (self.volume - 1.0).abs() > f32::EPSILON {
                    self.scratch.clear();
                    self.scratch.extend(block.iter().map(|s| s * self.volume));
                    self.scratch.as_slice()
                } else {
                    block
                };
                if let Err(e) = self.output.write(block) {
                    self.emit_error(etx, format!("写入输出失败: {e}"));
                    self.stop_current(etx, shared);
                    return;
                }
                let pos = dec.position_ms();
                shared.position_ms.store(pos, Ordering::Relaxed);
                let due = self
                    .last_pos_emit
                    .is_none_or(|t| t.elapsed() >= POSITION_EMIT_INTERVAL);
                if due {
                    self.last_pos_emit = Some(Instant::now());
                    let _ = etx.send(EngineEvent::PositionChanged { ms: pos });
                }
            }
            // 曲目自然结束:按循环模式决定去留。
            Ok(None) => {
                if let Some(index) = self.queue.current() {
                    let _ = etx.send(EngineEvent::TrackEnded { index });
                }
                match self.queue.next(false) {
                    Some(_) => {
                        self.open_current(true, etx, shared);
                    }
                    None => {
                        self.stop_current(etx, shared);
                        let _ = etx.send(EngineEvent::QueueEnded);
                    }
                }
            }
            Err(e) => {
                self.emit_error(etx, format!("解码中断: {e}"));
                self.stop_current(etx, shared);
            }
        }
    }

    /// 打开当前曲目并启动输出;`autoplay = false` 时输出立即挂起(载入但暂停)。
    fn open_current(&mut self, autoplay: bool, etx: &Sender<EngineEvent>, shared: &Shared) -> bool {
        let Some(index) = self.queue.current() else {
            return false;
        };
        let Some(item) = self.queue.item(index) else {
            return false;
        };

        match AudioDecoder::open(&item.path) {
            Ok(dec) => {
                let info = dec.info().clone();
                if let Err(e) = self.output.start(info.sample_rate, info.channels) {
                    self.emit_error(etx, format!("启动输出失败: {e}"));
                    self.set_state(PlayState::Stopped, etx, shared);
                    return false;
                }
                if !autoplay {
                    if let Err(e) = self.output.suspend() {
                        self.emit_error(etx, format!("挂起输出失败: {e}"));
                    }
                }
                self.decoder = Some(dec);
                shared
                    .duration_ms
                    .store(info.duration_ms, Ordering::Relaxed);
                shared.position_ms.store(0, Ordering::Relaxed);
                self.last_pos_emit = None;
                let _ = etx.send(EngineEvent::TrackStarted { index });
                true
            }
            Err(e) => {
                self.emit_error(etx, e.to_string());
                self.set_state(PlayState::Stopped, etx, shared);
                false
            }
        }
    }

    /// 切歌(用户触发);`forward = false` 为上一首。
    fn switch(&mut self, forward: bool, etx: &Sender<EngineEvent>, shared: &Shared) {
        let was_playing = self.state == PlayState::Playing;
        if forward {
            self.queue.next(true);
        } else {
            self.queue.prev();
        }
        if self.queue.current().is_some() {
            self.open_current(was_playing, etx, shared);
        } else {
            self.stop_current(etx, shared);
            let _ = etx.send(EngineEvent::QueueEnded);
        }
    }

    fn resume_playback(&mut self, etx: &Sender<EngineEvent>, shared: &Shared) {
        if self.decoder.is_some() {
            if let Err(e) = self.output.resume() {
                self.emit_error(etx, format!("恢复输出失败: {e}"));
                return;
            }
            self.set_state(PlayState::Playing, etx, shared);
        } else if self.open_current(true, etx, shared) {
            self.set_state(PlayState::Playing, etx, shared);
        }
    }

    fn stop_current(&mut self, etx: &Sender<EngineEvent>, shared: &Shared) {
        self.decoder = None;
        if let Err(e) = self.output.stop() {
            self.emit_error(etx, format!("停止输出失败: {e}"));
        }
        shared.position_ms.store(0, Ordering::Relaxed);
        self.set_state(PlayState::Stopped, etx, shared);
    }

    fn set_state(&mut self, s: PlayState, etx: &Sender<EngineEvent>, shared: &Shared) {
        if self.state != s {
            self.state = s;
            if let Ok(mut guard) = shared.state.lock() {
                *guard = s;
            }
            let _ = etx.send(EngineEvent::StateChanged(s));
        }
    }

    fn emit_error(&self, etx: &Sender<EngineEvent>, message: String) {
        let _ = etx.send(EngineEvent::Error { message });
    }
}

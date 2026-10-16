//! 播放队列与循环模式。
//!
//! 队列只做导航决策(下一首/上一首/跳转),不做任何 IO;
//! 引擎按返回的索引加载解码器。

use std::path::PathBuf;

/// 循环模式。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum RepeatMode {
    /// 不循环,播完队列停止。
    #[default]
    Off,
    /// 单曲循环(仅作用于曲目自然播完,用户手动切歌仍然前进)。
    One,
    /// 列表循环(播到队尾回到队首)。
    All,
}

/// 队列中的一个条目。
#[derive(Debug, Clone)]
pub struct QueueItem {
    /// 音频文件路径。
    pub path: PathBuf,
}

/// 简单可播种的 xorshift64* 随机源,只为随机播放提供确定性,避免引入 rand 依赖。
struct Rng(u64);

impl Rng {
    fn seed(seed: u64) -> Self {
        // xorshift 全零会退化,钳制为 1。
        Self(seed.max(1))
    }

    fn next_u64(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.0 = x;
        x
    }

    /// Fisher–Yates 洗牌。
    fn shuffle(&mut self, v: &mut [usize]) {
        for i in (1..v.len()).rev() {
            let j = (self.next_u64() as usize) % (i + 1);
            v.swap(i, j);
        }
    }
}

/// 播放队列:维护曲目列表、当前索引,并按循环/随机模式计算前进/后退。
pub struct PlayQueue {
    items: Vec<QueueItem>,
    /// 播放顺序(items 下标的一种排列;顺序模式即恒等排列)。
    order: Vec<usize>,
    /// 当前位置在 order 中的下标。
    pos: Option<usize>,
    repeat: RepeatMode,
    shuffle: bool,
    rng: Rng,
}

impl Default for PlayQueue {
    fn default() -> Self {
        Self {
            items: Vec::new(),
            order: Vec::new(),
            pos: None,
            repeat: RepeatMode::default(),
            shuffle: false,
            rng: Rng::seed(0x9E37_79B9_7F4A_7C15),
        }
    }
}

impl PlayQueue {
    pub fn new() -> Self {
        Self::default()
    }

    /// 替换整个队列;`start` 为初始曲目索引(越界则忽略)。
    pub fn set_items(&mut self, items: Vec<QueueItem>, start: Option<usize>) {
        self.items = items;
        self.order = (0..self.items.len()).collect();
        self.pos = start
            .filter(|&i| i < self.items.len())
            .and_then(|i| self.order.iter().position(|&x| x == i));
        if self.shuffle {
            self.reshuffle();
        }
    }

    pub fn len(&self) -> usize {
        self.items.len()
    }

    pub fn is_empty(&self) -> bool {
        self.items.is_empty()
    }

    /// 当前曲目(items 下标)。
    pub fn current(&self) -> Option<usize> {
        self.pos.map(|p| self.order[p])
    }

    pub fn item(&self, index: usize) -> Option<&QueueItem> {
        self.items.get(index)
    }

    pub fn repeat_mode(&self) -> RepeatMode {
        self.repeat
    }

    pub fn set_repeat_mode(&mut self, mode: RepeatMode) {
        self.repeat = mode;
    }

    pub fn is_shuffle(&self) -> bool {
        self.shuffle
    }

    /// 开/关随机播放。
    ///
    /// 开启时重排播放顺序,并把当前曲目放到首位;关闭时恢复列表顺序。
    pub fn set_shuffle(&mut self, on: bool) {
        if self.shuffle == on {
            return;
        }
        self.shuffle = on;
        if on {
            self.reshuffle();
        } else {
            // 恢复列表顺序,同时保持当前曲目不变。
            let cur = self.current();
            self.order = (0..self.items.len()).collect();
            self.pos = cur.and_then(|c| self.order.iter().position(|&i| i == c));
        }
    }

    /// 用户点击列表跳转到指定曲目。
    pub fn jump_to(&mut self, index: usize) -> bool {
        let Some(p) = self.order.iter().position(|&i| i == index) else {
            return false;
        };
        self.pos = Some(p);
        true
    }

    /// 前进。
    ///
    /// `user = true` 表示用户手动切歌(单曲循环不拦截);
    /// `user = false` 表示曲目自然播完。返回新的当前曲目,None 表示队列播放完毕。
    pub fn next(&mut self, user: bool) -> Option<usize> {
        self.advance(true, user)
    }

    /// 后退一首(总是换曲;到队首则绕到队尾)。
    pub fn prev(&mut self) -> Option<usize> {
        self.advance(false, true)
    }

    fn advance(&mut self, forward: bool, user: bool) -> Option<usize> {
        let p = self.pos?;
        let len = self.order.len();
        if len == 0 {
            return None;
        }

        // 单曲循环:自然播完时停留在当前曲目。
        if forward && !user && self.repeat == RepeatMode::One {
            return Some(self.order[p]);
        }

        let np = if forward {
            let t = p + 1;
            if t >= len {
                // 队尾:Off → 播放完毕;One/All → 回队首。
                if self.repeat == RepeatMode::Off {
                    return None;
                }
                0
            } else {
                t
            }
        } else {
            // 后退到队首则绕回队尾。
            if p == 0 {
                len - 1
            } else {
                p - 1
            }
        };

        self.pos = Some(np);
        Some(self.order[np])
    }

    /// 重排随机顺序,当前曲目(若有)置于首位。
    fn reshuffle(&mut self) {
        self.order = (0..self.items.len()).collect();
        self.rng.shuffle(&mut self.order);
        if let Some(cur) = self.current() {
            if let Some(p) = self.order.iter().position(|&i| i == cur) {
                self.order.swap(0, p);
            }
            self.pos = Some(0);
        } else {
            self.pos = None;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{PlayQueue, QueueItem, RepeatMode};

    fn queue(n: usize) -> PlayQueue {
        let mut q = PlayQueue::new();
        let items = (0..n)
            .map(|i| QueueItem {
                path: format!("/tmp/t{i}.wav").into(),
            })
            .collect();
        q.set_items(items, Some(0));
        q
    }

    fn item_paths(q: &PlayQueue) -> Vec<String> {
        q.order
            .iter()
            .map(|&i| q.item(i).unwrap().path.to_string_lossy().into_owned())
            .collect()
    }

    #[test]
    fn sequential_no_repeat() {
        let mut q = queue(3);
        assert_eq!(q.current(), Some(0));
        assert_eq!(q.next(false), Some(1));
        assert_eq!(q.next(false), Some(2));
        assert_eq!(q.next(false), None);
    }

    #[test]
    fn repeat_all_wraps() {
        let mut q = queue(3);
        q.set_repeat_mode(RepeatMode::All);
        assert_eq!(q.next(false), Some(1));
        assert_eq!(q.next(false), Some(2));
        assert_eq!(q.next(false), Some(0));
    }

    #[test]
    fn repeat_one_holds_only_on_natural_end() {
        let mut q = queue(3);
        q.set_repeat_mode(RepeatMode::One);
        assert_eq!(q.next(false), Some(0)); // 自然播完 → 原曲
        assert_eq!(q.next(true), Some(1)); // 用户切歌 → 前进
        assert_eq!(q.next(false), Some(1));
    }

    #[test]
    fn prev_wraps_at_head() {
        let mut q = queue(3);
        assert_eq!(q.prev(), Some(2));
        assert_eq!(q.prev(), Some(1));
        assert_eq!(q.prev(), Some(0));
    }

    #[test]
    fn shuffle_keeps_current_first_and_is_permutation() {
        let mut q = queue(8);
        q.next(false); // current = 1
        q.set_shuffle(true);
        assert_eq!(q.current(), Some(1));

        let paths = item_paths(&q);
        let mut sorted = paths.clone();
        sorted.sort();
        let mut expect = (0..8).map(|i| format!("/tmp/t{i}.wav")).collect::<Vec<_>>();
        expect.sort();
        assert_eq!(sorted, expect); // 仍是同一集合(排列)
        assert_eq!(paths.first().map(String::as_str), Some("/tmp/t1.wav")); // 当前曲目在首位

        q.set_shuffle(false);
        assert_eq!(q.current(), Some(1));
    }

    #[test]
    fn jump_and_boundaries() {
        let mut q = queue(3);
        assert!(q.jump_to(2));
        assert_eq!(q.current(), Some(2));
        assert!(!q.jump_to(3)); // 越界

        let mut empty = PlayQueue::new();
        assert_eq!(empty.next(false), None);
        assert!(empty.is_empty());
    }
}

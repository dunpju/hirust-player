//! 引擎线程集成测试:播放到结束、暂停、seek、队列推进、单曲循环、手动切歌。

mod common;

use std::time::{Duration, Instant};

use common::{temp_wav, write_sine_wav};
use player_core::{Engine, EngineEvent, MockOutput, PlayState, RepeatMode};

/// 轮询事件直到谓词命中或超时。
fn wait_event(
    engine: &Engine,
    mut pred: impl FnMut(&EngineEvent) -> bool,
    timeout: Duration,
) -> Option<EngineEvent> {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        while let Some(ev) = engine.try_event() {
            if pred(&ev) {
                return Some(ev);
            }
        }
        std::thread::sleep(Duration::from_millis(2));
    }
    None
}

fn state_changed(s: PlayState) -> impl FnMut(&EngineEvent) -> bool {
    move |ev| matches!(ev, EngineEvent::StateChanged(x) if *x == s)
}

fn is_queue_ended(ev: &EngineEvent) -> bool {
    matches!(ev, EngineEvent::QueueEnded)
}

/// 轮询引擎查询直到谓词命中或超时(慢速 CI runner 上的确定性等待)。
fn wait_until(engine: &Engine, mut pred: impl FnMut(&Engine) -> bool, timeout: Duration) -> bool {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        if pred(engine) {
            return true;
        }
        std::thread::sleep(Duration::from_millis(5));
    }
    false
}

#[test]
fn plays_single_track_to_queue_end() {
    let path = temp_wav("eng1");
    write_sine_wav(&path, 44_100, 500);

    let (mock, sink) = MockOutput::new(false); // 全速
    let engine = Engine::new(Box::new(mock));
    engine.set_queue(vec![path.clone()], Some(0));
    engine.play();

    assert!(wait_event(
        &engine,
        state_changed(PlayState::Playing),
        Duration::from_secs(5)
    )
    .is_some());
    // 注:不断言此处 state()==Playing——快速输出下整曲可能在断言前播完翻回
    // Stopped(Linux 慢速 runner 实测竞态);收到 Playing 事件即已证明状态转换。

    assert!(wait_event(&engine, is_queue_ended, Duration::from_secs(5)).is_some());
    assert_eq!(engine.state(), PlayState::Stopped);
    assert!(
        sink.total_samples() >= 22_050 - 2048,
        "samples = {}",
        sink.total_samples()
    );

    engine.shutdown();
    let _ = std::fs::remove_file(&path);
}

#[test]
fn pause_freezes_position_then_resumes() {
    let path = temp_wav("eng2");
    write_sine_wav(&path, 44_100, 2000);

    let (mock, _sink) = MockOutput::new(true); // 实时节奏
    let engine = Engine::new(Box::new(mock));
    engine.set_queue(vec![path.clone()], Some(0));
    engine.play();
    assert!(wait_event(
        &engine,
        state_changed(PlayState::Playing),
        Duration::from_secs(2)
    )
    .is_some());

    // 条件等待实际解码推进(慢速 runner 上固定 sleep 可能为 0,不再依赖墙钟)。
    let advanced = wait_until(&engine, |e| e.position_ms() >= 150, Duration::from_secs(5));
    assert!(advanced, "等待播放位置推进超时");

    engine.pause();
    assert!(wait_event(
        &engine,
        state_changed(PlayState::Paused),
        Duration::from_secs(5)
    )
    .is_some());

    let pos = engine.position_ms();
    std::thread::sleep(Duration::from_millis(150));
    assert_eq!(engine.position_ms(), pos, "暂停期间位置不得变化");
    assert!(pos >= 150, "pos = {pos}");

    engine.resume();
    assert!(wait_event(
        &engine,
        state_changed(PlayState::Playing),
        Duration::from_secs(5)
    )
    .is_some());
    engine.shutdown();
    let _ = std::fs::remove_file(&path);
}

#[test]
fn seek_moves_position() {
    let path = temp_wav("eng3");
    write_sine_wav(&path, 44_100, 2000);

    let (mock, _sink) = MockOutput::new(true);
    let engine = Engine::new(Box::new(mock));
    engine.set_queue(vec![path.clone()], Some(0));
    engine.play();
    assert!(wait_event(
        &engine,
        state_changed(PlayState::Playing),
        Duration::from_secs(2)
    )
    .is_some());

    engine.seek_ms(1000);
    // 条件等待 seek 生效(位置 ≥950),再暂停冻结后断言区间,避免慢速 runner 抖动。
    let advanced = wait_until(&engine, |e| e.position_ms() >= 950, Duration::from_secs(5));
    assert!(advanced, "seek 后位置未推进");

    engine.pause();
    assert!(wait_event(
        &engine,
        state_changed(PlayState::Paused),
        Duration::from_secs(5)
    )
    .is_some());
    let pos = engine.position_ms();
    assert!((950..=1600).contains(&pos), "pos = {pos}");
    engine.shutdown();
    let _ = std::fs::remove_file(&path);
}

/// 收集 TrackStarted 计数,直到 QueueEnded 或超时。
fn drain_until_queue_ended(engine: &Engine, timeout: Duration) -> usize {
    let mut started = 0;
    let deadline = Instant::now() + timeout;
    loop {
        match engine.try_event() {
            Some(EngineEvent::TrackStarted { .. }) => started += 1,
            Some(EngineEvent::QueueEnded) => return started,
            Some(_) => {}
            None => {
                assert!(Instant::now() < deadline, "等待 QueueEnded 超时");
                std::thread::sleep(Duration::from_millis(2));
            }
        }
    }
}

#[test]
fn queue_advances_through_two_tracks() {
    let a = temp_wav("eng4a");
    let b = temp_wav("eng4b");
    write_sine_wav(&a, 44_100, 200);
    write_sine_wav(&b, 48_000, 200); // 不同采样率,验证输出重配置

    let (mock, _sink) = MockOutput::new(false);
    let engine = Engine::new(Box::new(mock));
    engine.set_queue(vec![a.clone(), b.clone()], Some(0));
    engine.play();

    assert_eq!(drain_until_queue_ended(&engine, Duration::from_secs(5)), 2);
    assert_eq!(engine.state(), PlayState::Stopped);

    engine.shutdown();
    let _ = std::fs::remove_file(&a);
    let _ = std::fs::remove_file(&b);
}

#[test]
fn repeat_one_restarts_same_track() {
    let path = temp_wav("eng5");
    write_sine_wav(&path, 44_100, 150);

    let (mock, _sink) = MockOutput::new(false);
    let engine = Engine::new(Box::new(mock));
    engine.set_queue(vec![path.clone()], Some(0));
    engine.set_repeat(RepeatMode::One);
    engine.play();

    // 单曲循环:等待同一曲目两次自然播完。
    let mut ended = 0;
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        match engine.try_event() {
            Some(EngineEvent::TrackEnded { .. }) => ended += 1,
            Some(_) => {}
            None => std::thread::sleep(Duration::from_millis(1)),
        }
        if ended >= 2 {
            break;
        }
        assert!(Instant::now() < deadline, "等待两次 TrackEnded 超时");
    }

    engine.stop();
    assert!(wait_event(
        &engine,
        state_changed(PlayState::Stopped),
        Duration::from_secs(2)
    )
    .is_some());
    engine.shutdown();
    let _ = std::fs::remove_file(&path);
}

#[test]
fn user_next_switches_track_immediately() {
    let a = temp_wav("eng6a");
    let b = temp_wav("eng6b");
    write_sine_wav(&a, 44_100, 2000);
    write_sine_wav(&b, 44_100, 200);

    let (mock, _sink) = MockOutput::new(true);
    let engine = Engine::new(Box::new(mock));
    engine.set_queue(vec![a.clone(), b.clone()], Some(0));
    engine.play();
    assert!(wait_event(
        &engine,
        state_changed(PlayState::Playing),
        Duration::from_secs(2)
    )
    .is_some());

    std::thread::sleep(Duration::from_millis(150));
    engine.next(); // 首曲还很长,手动切到 b

    // next 应切到曲目 1(TrackStarted),随后 b 自然播完、队列结束。
    assert!(wait_event(
        &engine,
        |ev| matches!(ev, EngineEvent::TrackStarted { index: 1 }),
        Duration::from_secs(2)
    )
    .is_some());
    assert!(wait_event(&engine, is_queue_ended, Duration::from_secs(5)).is_some());
    assert_eq!(engine.state(), PlayState::Stopped);

    engine.shutdown();
    let _ = std::fs::remove_file(&a);
    let _ = std::fs::remove_file(&b);
}

#[test]
fn volume_scales_output_samples() {
    let path = temp_wav("eng7");
    write_sine_wav(&path, 44_100, 300);

    let run = |volume: f32| -> f32 {
        let (mock, sink) = MockOutput::new(false);
        let engine = Engine::new(Box::new(mock));
        engine.set_volume(volume);
        engine.set_queue(vec![path.clone()], Some(0));
        engine.play();
        assert!(wait_event(&engine, is_queue_ended, Duration::from_secs(5)).is_some());
        engine.shutdown();
        sink.snapshot().iter().fold(0.0f32, |m, s| m.max(s.abs()))
    };

    let full = run(1.0);
    let half = run(0.5);
    assert!(full > 0.2, "满音量应有有效幅度: {full}");
    assert!(
        (half / full - 0.5).abs() < 0.05,
        "0.5 音量应衰减约一半: full={full} half={half}"
    );
    let _ = std::fs::remove_file(&path);
}

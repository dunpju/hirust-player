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
        Duration::from_secs(2)
    )
    .is_some());
    assert_eq!(engine.state(), PlayState::Playing);

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

    std::thread::sleep(Duration::from_millis(200));
    engine.pause();
    assert!(wait_event(
        &engine,
        state_changed(PlayState::Paused),
        Duration::from_secs(2)
    )
    .is_some());

    let pos = engine.position_ms();
    std::thread::sleep(Duration::from_millis(150));
    assert_eq!(engine.position_ms(), pos, "暂停期间位置不得变化");
    assert!((100..=600).contains(&pos), "pos = {pos}");

    engine.resume();
    assert!(wait_event(
        &engine,
        state_changed(PlayState::Playing),
        Duration::from_secs(2)
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

    std::thread::sleep(Duration::from_millis(100));
    engine.seek_ms(1000);
    std::thread::sleep(Duration::from_millis(120));

    let pos = engine.position_ms();
    assert!((950..=1300).contains(&pos), "pos = {pos}");
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

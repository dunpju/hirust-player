//! 事件泵:把 player-core 引擎事件经 JNI 转发给 Kotlin `AudioBridge` 的静态回调。
//!
//! 独立线程阻塞消费 Receiver;每次事件临时 attach JVM。
//! Kotlin 侧回调须自行切换到主线程(见 PlayerViewModel)。

use std::sync::mpsc::Receiver;
use std::sync::Arc;

use jni::objects::{GlobalRef, JClass, JObject, JValue};
use jni::JavaVM;
use log::warn;

use player_core::{EngineEvent, PlayState};

/// 启动事件泵线程(引擎创建时调用一次)。
pub(crate) fn spawn(vm: Arc<JavaVM>, class: GlobalRef, events: Receiver<EngineEvent>) {
    let spawned = std::thread::Builder::new()
        .name("hirust-event-pump".to_string())
        .spawn(move || pump(vm, class, events));
    // 进程级资源耗尽,按 CLAUDE.md §11 属可 panic 的例外。
    spawned.expect("事件泵线程创建失败");
}

fn pump(vm: Arc<JavaVM>, class: GlobalRef, events: Receiver<EngineEvent>) {
    for ev in events.iter() {
        let mut env = match vm.attach_current_thread() {
            Ok(env) => env,
            Err(e) => {
                warn!("事件泵 attach JVM 失败: {e}");
                break;
            }
        };
        let jclass: &JClass<'_> = class.as_obj().into();

        let result = match ev {
            EngineEvent::StateChanged(s) => env.call_static_method(
                jclass,
                "onStateChanged",
                "(I)V",
                &[JValue::Int(state_to_int(s))],
            ),
            EngineEvent::PositionChanged { ms } => env.call_static_method(
                jclass,
                "onPositionChanged",
                "(J)V",
                &[JValue::Long(ms as i64)],
            ),
            EngineEvent::TrackStarted { index } => env.call_static_method(
                jclass,
                "onTrackStarted",
                "(I)V",
                &[JValue::Int(index as i32)],
            ),
            EngineEvent::TrackEnded { index } => {
                env.call_static_method(jclass, "onTrackEnded", "(I)V", &[JValue::Int(index as i32)])
            }
            EngineEvent::QueueEnded => env.call_static_method(jclass, "onQueueEnded", "()V", &[]),
            EngineEvent::Error { message } => match env.new_string(message) {
                Ok(s) => env.call_static_method(
                    jclass,
                    "onError",
                    "(Ljava/lang/String;)V",
                    &[JValue::Object(&JObject::from(s))],
                ),
                Err(e) => Err(e),
            },
        };

        if let Err(e) = result {
            warn!("事件回调失败: {e}");
        }

        // 防御:未清理的 pending exception 会让本线程后续所有 JNI 调用永久失败
        // (症状即"事件链整体死亡,仅剩主动轮询可用"),每条事件后检查并清理。
        match env.exception_check() {
            Ok(true) => {
                let _ = env.exception_clear();
            }
            Ok(false) => {}
            Err(e) => warn!("exception_check 失败: {e}"),
        }
    }
}

/// PlayState → Kotlin 侧常量(PlayStates)。
pub(crate) fn state_to_int(s: PlayState) -> i32 {
    match s {
        PlayState::Stopped => 0,
        PlayState::Playing => 1,
        PlayState::Paused => 2,
    }
}

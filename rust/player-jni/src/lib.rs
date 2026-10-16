//! # player-jni
//!
//! Kotlin ↔ Rust 桥接层(cdylib → libplayer_jni.so)。
//!
//! 约定(CLAUDE.md §8):
//! - Kotlin 侧唯一入口为 `com.hirust.player.audio.AudioBridge`;
//! - 本 crate 只做参数/返回值翻译与异常映射,不含业务逻辑;
//! - JNI 函数命名固定为 `Java_com_hirust_player_audio_AudioBridge_<方法名>`;
//! - 事件经 [`events`] 泵线程回调 Kotlin 静态方法;
//! - 音频输出经 [`output::JniOutput`] 调 Kotlin 托管的 AudioTrack(D2 修订)。

mod events;
mod output;

use std::sync::{Arc, Mutex, MutexGuard};

use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jfloat, jint, jlong, jstring};
use jni::JNIEnv;
use log::warn;

use player_core::{Engine, RepeatMode};

use events::state_to_int;
use output::{JniOutput, BRIDGE_CLASS};

/// 全局引擎实例(engineStart 创建,release 释放)。
static ENGINE: Mutex<Option<Engine>> = Mutex::new(None);

fn lock() -> MutexGuard<'static, Option<Engine>> {
    // 中毒锁属进程内异常,保守恢复使用。
    ENGINE
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn with_engine(f: impl FnOnce(&Engine)) {
    if let Some(engine) = lock().as_ref() {
        f(engine);
    } else {
        warn!("引擎尚未启动,命令被忽略");
    }
}

/// 带回退值的查询(引擎未启动时返回 `default`)。
fn query_engine<R>(default: R, f: impl FnOnce(&Engine) -> R) -> R {
    match lock().as_ref() {
        Some(engine) => f(engine),
        None => {
            warn!("引擎尚未启动,查询返回默认值");
            default
        }
    }
}

/// Android 日志与 panic hook(tag=hirust);宿主构建为空操作。
fn init_logging() {
    #[cfg(target_os = "android")]
    {
        android_logger::init_once(
            android_logger::Config::default()
                .with_tag("hirust")
                .with_max_level(log::LevelFilter::Info),
        );
        std::panic::set_hook(Box::new(|info| log::error!("Rust panic: {info}")));
    }
}

/// 返回 player-core 引擎版本(Cargo workspace version)。
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hirust_player_audio_AudioBridge_version(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    env.new_string(player_core::VERSION)
        .expect("版本为编译期 ASCII,不含 NUL")
        .into_raw()
}

/// 创建引擎与事件泵;幂等(重复调用直接返回)。
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hirust_player_audio_AudioBridge_engineStart(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) {
    init_logging();
    let mut guard = lock();
    if guard.is_some() {
        return;
    }

    let started = (|| -> Result<(), String> {
        let vm = Arc::new(
            env.get_java_vm()
                .map_err(|e| format!("获取 JavaVM 失败: {e}"))?,
        );
        let class = env
            .find_class(BRIDGE_CLASS)
            .map_err(|e| format!("查找 AudioBridge 类失败: {e}"))?;
        let class = env
            .new_global_ref(class)
            .map_err(|e| format!("创建全局引用失败: {e}"))?;

        let output = JniOutput::new(Arc::clone(&vm), class.clone());
        let mut engine = Engine::new(Box::new(output));
        let rx = engine.take_events();
        events::spawn(vm, class, rx);
        *guard = Some(engine);
        Ok(())
    })();

    if let Err(message) = started {
        warn!("引擎启动失败: {message}");
        let _ = env.throw_new("java/lang/IllegalStateException", message);
    }
}

/// 关停并释放引擎(进程退出前/服务销毁时调用)。
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hirust_player_audio_AudioBridge_release(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) {
    if let Some(engine) = lock().take() {
        engine.shutdown();
    }
}

/// 替换播放队列;`start < 0` 表示不指定初始曲目。
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hirust_player_audio_AudioBridge_setQueue(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    paths: JObjectArray<'_>,
    start: jint,
) {
    let items = match string_array_to_paths(&mut env, &paths) {
        Ok(v) => v,
        Err(message) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", message);
            return;
        }
    };
    let start = if start < 0 {
        None
    } else {
        Some(start as usize)
    };
    with_engine(|e| e.set_queue(items, start));
}

fn string_array_to_paths(
    env: &mut JNIEnv<'_>,
    array: &JObjectArray<'_>,
) -> Result<Vec<std::path::PathBuf>, String> {
    let n = env
        .get_array_length(array)
        .map_err(|e| format!("读取数组长度失败: {e}"))?;
    let mut out = Vec::with_capacity(n.max(0) as usize);
    for i in 0..n {
        let elem = env
            .get_object_array_element(array, i)
            .map_err(|e| format!("读取数组元素 {i} 失败: {e}"))?;
        let s: JString = elem.into();
        let java = env
            .get_string(&s)
            .map_err(|e| format!("读取字符串失败: {e}"))?;
        out.push(std::path::PathBuf::from(
            java.to_string_lossy().into_owned(),
        ));
    }
    Ok(out)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hirust_player_audio_AudioBridge_play(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) {
    with_engine(Engine::play);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hirust_player_audio_AudioBridge_pause(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) {
    with_engine(Engine::pause);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hirust_player_audio_AudioBridge_resume(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) {
    with_engine(Engine::resume);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hirust_player_audio_AudioBridge_stop(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) {
    with_engine(Engine::stop);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hirust_player_audio_AudioBridge_next(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) {
    with_engine(Engine::next);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hirust_player_audio_AudioBridge_prev(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) {
    with_engine(Engine::prev);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hirust_player_audio_AudioBridge_seekMs(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    ms: jlong,
) {
    with_engine(|e| e.seek_ms(ms.max(0) as u64));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hirust_player_audio_AudioBridge_positionMs(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jlong {
    query_engine(0, |e| e.position_ms() as jlong)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hirust_player_audio_AudioBridge_durationMs(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jlong {
    query_engine(0, |e| e.duration_ms() as jlong)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hirust_player_audio_AudioBridge_state(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jint {
    query_engine(0, |e| state_to_int(e.state()))
}

/// 循环模式:0 关 / 1 单曲 / 2 列表(与 Kotlin RepeatModes 对应)。
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hirust_player_audio_AudioBridge_setRepeatMode(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    mode: jint,
) {
    let mode = match mode {
        1 => RepeatMode::One,
        2 => RepeatMode::All,
        _ => RepeatMode::Off,
    };
    with_engine(move |e| e.set_repeat(mode));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hirust_player_audio_AudioBridge_setShuffle(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    on: jboolean,
) {
    with_engine(move |e| e.set_shuffle(on != 0));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hirust_player_audio_AudioBridge_setVolume(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    volume: jfloat,
) {
    with_engine(move |e| e.set_volume(volume));
}

/// 读取曲目标签,返回 JSON:{"title":..,"artist":..,"album":..,"durationMs":N}
/// (空值字段省略)。失败时抛 IllegalStateException。
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hirust_player_audio_AudioBridge_trackInfo(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    path: JString<'_>,
) -> jstring {
    let json = match jstring_to_path(&mut env, &path)
        .and_then(|p| player_core::metadata::read_tags(&p).map_err(|e| e.to_string()))
    {
        Ok(tags) => tags_to_json(&tags),
        Err(message) => {
            let _ = env.throw_new("java/lang/IllegalStateException", message);
            return std::ptr::null_mut();
        }
    };
    match env.new_string(json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// 读取内嵌封面字节(无封面或失败返回 null)。
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hirust_player_audio_AudioBridge_trackCover(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    path: JString<'_>,
) -> jbyteArray {
    let cover = match jstring_to_path(&mut env, &path)
        .and_then(|p| player_core::metadata::read_cover(&p).map_err(|e| e.to_string()))
    {
        Ok(c) => c,
        Err(_) => return std::ptr::null_mut(),
    };
    let Some(cover) = cover else {
        return std::ptr::null_mut();
    };
    match env.byte_array_from_slice(&cover.data) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn jstring_to_path(env: &mut JNIEnv<'_>, s: &JString<'_>) -> Result<std::path::PathBuf, String> {
    let java = env
        .get_string(s)
        .map_err(|e| format!("读取字符串失败: {e}"))?;
    Ok(std::path::PathBuf::from(
        java.to_string_lossy().into_owned(),
    ))
}

fn tags_to_json(tags: &player_core::metadata::TrackTags) -> String {
    let mut out = String::with_capacity(128);
    out.push('{');
    let mut first = true;
    let field = |out: &mut String, first: &mut bool, key: &str, value: &Option<String>| {
        if let Some(v) = value {
            if !*first {
                out.push(',');
            }
            *first = false;
            out.push_str(&format!("\"{key}\":\"{}\"", json_escape(v)));
        }
    };
    field(&mut out, &mut first, "title", &tags.title);
    field(&mut out, &mut first, "artist", &tags.artist);
    field(&mut out, &mut first, "album", &tags.album);
    if !first {
        out.push(',');
    }
    out.push_str(&format!("\"durationMs\":{}", tags.duration_ms));
    out.push('}');
    out
}

fn json_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out
}

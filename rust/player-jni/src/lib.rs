//! # player-jni
//!
//! Kotlin ↔ Rust 桥接层(cdylib → libplayer_jni.so)。
//!
//! 约定(CLAUDE.md §8):
//! - Kotlin 侧唯一入口为 `com.hirust.player.audio.AudioBridge`;
//! - 本 crate 只做参数/返回值翻译与异常转换,不含业务逻辑;
//! - JNI 函数命名固定为 `Java_com_hirust_player_audio_AudioBridge_<方法名>`。

use jni::{objects::JClass, sys::jstring, JNIEnv};

/// 返回 player-core 引擎版本(Cargo workspace version)。
///
/// 对应 Kotlin:`AudioBridge.version(): String`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hirust_player_audio_AudioBridge_version(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    env.new_string(player_core::VERSION)
        .expect("版本为编译期 ASCII,不含 NUL")
        .into_raw()
}

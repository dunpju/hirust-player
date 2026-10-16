package com.hirust.player.audio

/**
 * Rust 引擎的 Kotlin 侧唯一入口(CLAUDE.md §8)。
 *
 * 每个 `external fun` 与 rust/player-jni 中的
 * `Java_com_hirust_player_audio_AudioBridge_<同名>` JNI 函数一一对应;
 * 新增方法必须两侧同步并更新契约表。
 */
object AudioBridge {

    init {
        System.loadLibrary("player_jni")
    }

    /** player-core 引擎版本(来自 rust workspace 的 Cargo.toml)。 */
    external fun version(): String
}

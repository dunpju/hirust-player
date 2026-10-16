package com.hirust.player.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.hirust.player.Settings

/** 播放状态常量(Rust `PlayState` 的 JNI 映射)。 */
object PlayStates {
    const val STOPPED = 0
    const val PLAYING = 1
    const val PAUSED = 2
}

/** 循环模式常量(Rust `RepeatMode` 的 JNI 映射)。 */
object RepeatModes {
    const val OFF = 0
    const val ONE = 1
    const val ALL = 2
}

/**
 * Rust 引擎事件回调。
 *
 * 注意:由 Rust 事件泵线程(JNI attached)调用,实现方须自行切换到主线程。
 */
interface EngineListener {
    fun onStateChanged(state: Int) {}
    fun onPositionChanged(positionMs: Long) {}
    fun onTrackStarted(index: Int) {}
    fun onTrackEnded(index: Int) {}
    fun onQueueEnded() {}
    fun onError(message: String) {}
}

/**
 * Rust 引擎的 Kotlin 侧唯一入口(CLAUDE.md §8)。
 *
 * - `external fun` 与 rust/player-jni 中
 *   `Java_com_hirust_player_audio_AudioBridge_<同名>` JNI 函数一一对应;
 * - `@JvmStatic` 方法由 Rust 调用:事件回调(onXxx)与输出宿主(outputXxx);
 * - 新增方法必须两侧同步并更新契约表。
 */
object AudioBridge {

    /** UI 注册的全局事件监听(事件泵线程调用)。 */
    @Volatile
    var listener: EngineListener? = null

    /** 应用上下文(PlaybackService onCreate 注入,用于读取设置)。 */
    @Volatile
    private var appContext: Context? = null

    /** 注入应用上下文;在 engineStart 之前调用。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    init {
        System.loadLibrary("player_jni")
        engineStart()
    }

    // ---- 命令(Kotlin → Rust)----

    external fun version(): String
    external fun release()
    external fun setQueue(paths: Array<String>, start: Int)
    external fun play()
    external fun pause()
    external fun resume()
    external fun stop()
    external fun next()
    external fun prev()
    external fun seekMs(ms: Long)
    external fun positionMs(): Long
    external fun durationMs(): Long
    external fun state(): Int
    external fun setRepeatMode(mode: Int)
    external fun setShuffle(on: Boolean)
    external fun setVolume(volume: Float)

    /** 读取曲目标签,JSON:{"title":..,"artist":..,"album":..,"durationMs":N}(空字段省略)。 */
    external fun trackInfo(path: String): String

    /** 读取内嵌封面字节;无封面返回 null。 */
    external fun trackCover(path: String): ByteArray?
    private external fun engineStart()

    // ---- 事件(Rust → Kotlin,事件泵线程)----

    @JvmStatic
    fun onStateChanged(state: Int) {
        listener?.onStateChanged(state)
    }

    @JvmStatic
    fun onPositionChanged(positionMs: Long) {
        listener?.onPositionChanged(positionMs)
    }

    @JvmStatic
    fun onTrackStarted(index: Int) {
        listener?.onTrackStarted(index)
    }

    @JvmStatic
    fun onTrackEnded(index: Int) {
        listener?.onTrackEnded(index)
    }

    @JvmStatic
    fun onQueueEnded() {
        listener?.onQueueEnded()
    }

    @JvmStatic
    fun onError(message: String) {
        listener?.onError(message)
    }

    // ---- 输出宿主(Rust JniOutput → AudioTrack,引擎线程)----

    private var track: AudioTrack? = null

    @JvmStatic
    fun outputStart(sampleRate: Int, channels: Int): Boolean {
        outputStop()
        val channelMask =
            if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf =
            AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBuf <= 0) return false
        // 缓冲时长可配置(Settings.bufferMs,下一曲目生效);f32 每帧 = 声道数 × 4 字节。
        val bufferMs = appContext?.let { Settings.bufferMs(it) } ?: Settings.DEFAULT_BUFFER_MS
        val bufferBytes =
            maxOf(minBuf * 2, sampleRate * channels * 4 * bufferMs / 1000)
        val built = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        built.play()
        track = built
        return true
    }

    /** 阻塞写入交织 PCM;返回写入样本数,负数为错误码。 */
    @JvmStatic
    fun outputWrite(samples: FloatArray): Int {
        val t = track ?: return -1
        return t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
    }

    @JvmStatic
    fun outputSuspend() {
        track?.pause()
    }

    @JvmStatic
    fun outputResume() {
        track?.play()
    }

    @JvmStatic
    fun outputStop() {
        track?.let { t ->
            runCatching {
                t.pause()
                t.stop()
                t.release()
            }
        }
        track = null
    }
}

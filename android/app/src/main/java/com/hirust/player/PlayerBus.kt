package com.hirust.player

import android.content.Context
import android.net.ContentUris
import android.net.Uri
import com.hirust.player.audio.AudioBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** 音频条目元数据(MediaStore 扫描结果)。 */
data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val path: String,
    val albumId: Long? = null,
) {
    /** MediaStore 专辑封面 URI(可能为 null)。 */
    val albumArtUri: Uri?
        get() = albumId?.let {
            ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"),
                it,
            )
        }
}

/** 全局播放状态快照:服务(事件源)与 UI(观察者)之间的单一事实来源。 */
data class BusState(
    val tracks: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** 循环模式:RepeatModes.OFF/ONE/ALL。 */
    val repeatMode: Int = 0,
    val shuffle: Boolean = false,
    val volume: Float = 1.0f,
    val error: String? = null,
)

/**
 * 播放状态总线:PlaybackService 写、UI 读;StateFlow 线程安全。
 *
 * 同时负责队列持久化/恢复(进程被杀后按路径恢复,暂停态,不自动续播)。
 */
object PlayerBus {

    private const val PREFS = "player_bus"
    private const val KEY_PATHS = "paths"
    private const val KEY_TITLES = "titles"
    private const val KEY_INDEX = "index"
    private const val SEP = "\n"

    val state: StateFlow<BusState> = MutableStateFlow(BusState())

    /** 恢复时最近一次保存的曲目下标。 */
    var savedIndex: Int = 0
        private set

    fun update(f: (BusState) -> BusState) {
        (state as MutableStateFlow).value = f(state.value)
    }

    fun setTracks(tracks: List<Track>) = update { it.copy(tracks = tracks) }

    /** 从指定曲目开始播放(替换队列为当前列表)。 */
    fun playIndex(index: Int) {
        val s = state.value
        if (index !in s.tracks.indices) return
        val paths = s.tracks.map { it.path }.toTypedArray()
        AudioBridge.setQueue(paths, index)
        AudioBridge.play()
    }

    /** 设置循环模式(同时下发引擎并更新状态)。 */
    fun setRepeatMode(mode: Int) {
        AudioBridge.setRepeatMode(mode)
        update { it.copy(repeatMode = mode) }
    }

    /** 设置随机播放。 */
    fun setShuffle(on: Boolean) {
        AudioBridge.setShuffle(on)
        update { it.copy(shuffle = on) }
    }

    fun toggleShuffle() = setShuffle(!state.value.shuffle)

    /** 设置音量 [0,1](用户音量;引擎实际音量 = 用户音量 × duck 系数)。 */
    fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        update { it.copy(volume = v) }
        applyEngineVolume()
    }

    /** 焦点压低(ducker):瞬时压到用户音量的 20%。 */
    fun setDucked(on: Boolean) {
        if (ducked == on) return
        ducked = on
        applyEngineVolume()
    }

    private var ducked = false

    private fun applyEngineVolume() {
        val user = state.value.volume
        val effective = if (ducked) user * 0.2f else user
        AudioBridge.setVolume(effective)
    }

    // ---- 持久化(路径 + 标题 + 当前下标;恢复时校验文件仍可读) ----

    fun persist(context: Context) {
        val s = state.value
        if (s.tracks.isEmpty()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PATHS, s.tracks.joinToString(SEP) { it.path })
            .putString(KEY_TITLES, s.tracks.joinToString(SEP) { it.title })
            .putInt(KEY_INDEX, s.currentIndex.coerceAtLeast(0))
            .apply()
    }

    fun restore(context: Context): List<Track> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val paths = prefs.getString(KEY_PATHS, null)?.split(SEP) ?: return emptyList()
        val titles = prefs.getString(KEY_TITLES, null)?.split(SEP) ?: emptyList()
        savedIndex = prefs.getInt(KEY_INDEX, 0)
        return paths.mapIndexedNotNull { i, path ->
            val file = File(path)
            if (file.canRead()) {
                Track(
                    id = i.toLong(),
                    title = titles.getOrElse(i) { file.name },
                    artist = "",
                    durationMs = 0,
                    path = path,
                )
            } else {
                null
            }
        }
    }
}

/**
 * 内嵌封面磁盘缓存:Rust `trackCover` 提取 → filesDir/covers/<hash>(阻塞 IO,调用方自行切线程)。
 */
object CoverStore {

    private const val DIR = "covers"

    private fun fileFor(context: Context, path: String): File =
        File(File(context.filesDir, DIR), Integer.toHexString(path.hashCode()))

    fun load(context: Context, path: String): File? {
        val cached = fileFor(context, path)
        if (cached.isFile) return cached
        val bytes = runCatching { AudioBridge.trackCover(path) }.getOrNull() ?: return null
        cached.parentFile?.mkdirs()
        return runCatching {
            cached.writeBytes(bytes)
            cached
        }.getOrNull()
    }
}

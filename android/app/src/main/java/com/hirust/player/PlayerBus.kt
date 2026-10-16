package com.hirust.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import com.hirust.player.audio.AudioBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** 文件夹播放列表(一个目录 = 一个播放列表,路径持久化)。 */
data class Folder(
    val name: String,
    val path: String,
)

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

/**
 * 全局播放状态快照:服务(事件源)与 UI(观察者)之间的单一事实来源。
 *
 * 队列(`tracks`)与视图(`view`)分离:切列表只换 view,不打断正在播放的队列;
 * 点播时才把 view 装载为队列。`viewSource/queueSource` 一致时列表高亮当前曲目。
 */
data class BusState(
    /** 引擎播放队列(EnginePlayer 时间线也基于此)。 */
    val tracks: List<Track> = emptyList(),
    /** UI 正在展示的列表(全部音乐或某文件夹)。 */
    val view: List<Track> = emptyList(),
    /** 视图来源:null = 全部音乐,否则为文件夹路径。 */
    val viewSource: String? = null,
    /** 队列来源:同上;恢复场景为哨兵值(不与任何视图相等,避免错误高亮)。 */
    val queueSource: String? = RESTORED,
    /** 已添加的文件夹播放列表。 */
    val folders: List<Folder> = emptyList(),
    val currentIndex: Int = -1,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** 循环模式:RepeatModes.OFF/ONE/ALL。 */
    val repeatMode: Int = 0,
    val shuffle: Boolean = false,
    val volume: Float = 1.0f,
    val error: String? = null,
) {
    companion object {
        /** 进程被杀恢复的队列来源哨兵。 */
        const val RESTORED = "\u0000restored"
    }
}

/**
 * 播放状态总线:PlaybackService 写、UI 读;StateFlow 线程安全。
 * 队列持久化/恢复 + 文件夹列表持久化。
 */
object PlayerBus {

    private const val PREFS = "player_bus"
    private const val KEY_PATHS = "paths"
    private const val KEY_TITLES = "titles"
    private const val KEY_INDEX = "index"
    private const val KEY_FOLDERS = "folders"
    private const val SEP = "\n"

    val state: StateFlow<BusState> = MutableStateFlow(BusState())

    /** 恢复时最近一次保存的曲目下标。 */
    var savedIndex: Int = 0
        private set

    fun update(f: (BusState) -> BusState) {
        (state as MutableStateFlow).value = f(state.value)
    }

    /** 切换 UI 视图(全部音乐/某文件夹),不影响播放队列。 */
    fun setView(tracks: List<Track>, source: String?) = update {
        it.copy(view = tracks, viewSource = source)
    }

    /** 设置文件夹列表(添加/删除后调用,同时持久化)。 */
    fun setFolders(context: Context, folders: List<Folder>) {
        update { it.copy(folders = folders) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FOLDERS, folders.joinToString(SEP) { it.path })
            .apply()
    }

    /** 恢复持久化的文件夹列表(服务启动时调用)。 */
    fun restoreFolders(context: Context) {
        if (state.value.folders.isNotEmpty()) return
        val paths = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FOLDERS, null)
            ?.split(SEP)
            .orEmpty()
        val folders = paths.filter { it.isNotBlank() && File(it).isDirectory }
            .map { Folder(name = it.trimEnd('/').substringAfterLast('/'), path = it) }
        if (folders.isNotEmpty()) update { it.copy(folders = folders) }
    }

    /** 点播当前视图中的曲目:把视图装载为队列并从 index 播放。 */
    fun playFromView(index: Int) {
        val s = state.value
        playQueue(s.view, s.viewSource, index)
    }

    /** 会话侧(通知 seek 到指定曲目):按队列下标重播。 */
    fun playIndex(index: Int) {
        val s = state.value
        playQueue(s.tracks, s.queueSource, index)
    }

    /** 装载队列并播放(替换队列,从 index 开始)。 */
    fun playQueue(tracks: List<Track>, source: String?, index: Int) {
        if (index !in tracks.indices) return
        AudioBridge.setQueue(tracks.map { it.path }.toTypedArray(), index)
        update { it.copy(tracks = tracks, queueSource = source, currentIndex = -1) }
        AudioBridge.play()
    }

    /** 进程被杀后恢复队列(暂停态,来源为哨兵)。 */
    fun restoreQueue(tracks: List<Track>, index: Int) {
        AudioBridge.setQueue(tracks.map { it.path }.toTypedArray(), index)
        update { it.copy(tracks = tracks, queueSource = BusState.RESTORED, currentIndex = -1) }
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

    // ---- 队列持久化(路径 + 标题 + 当前下标;恢复时校验文件仍可读) ----

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

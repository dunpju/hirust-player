package com.hirust.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import com.hirust.player.audio.AudioBridge
import com.hirust.player.audio.PlayStates
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
    /** 队列来源:同上;恢复时写回持久化的来源(视图与来源一致即高亮上次曲目)。 */
    val queueSource: String? = null,
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
    /** 播放速率档位(0.5/1.0/1.5/2.0)。 */
    val speed: Float = 1.0f,
    val error: String? = null,
)

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
    private const val KEY_SOURCE = "queue_source"
    private const val KEY_LAST_DOMAIN = "last_domain"
    private const val SEP = "\n"

    val state: StateFlow<BusState> = MutableStateFlow(BusState())

    /** 恢复时最近一次保存的曲目下标。 */
    var savedIndex: Int = 0
        private set

    /** 恢复时最近一次保存的队列来源(null = 全部音乐;目录不存在则回落全部)。 */
    var savedSource: String? = null
        private set

    /** 直接读取持久化的上次队列来源(null = 全部);不依赖服务 restore 时序。 */
    fun lastSource(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SOURCE, null)
            ?.takeIf { it.isNotBlank() }

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

    /** 进程被杀后恢复队列(暂停态;直接写持久化下标——恢复态没有 TrackStarted 事件)。 */
    fun restoreQueue(tracks: List<Track>, index: Int, source: String?) {
        if (tracks.isEmpty()) return
        AudioBridge.setQueue(tracks.map { it.path }.toTypedArray(), index)
        // 下标钳制(恢复场景持久化值可能越界)。
        val clamped = index.coerceIn(0, tracks.lastIndex)
        update {
            it.copy(tracks = tracks, queueSource = source, currentIndex = clamped)
        }
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

    /** 倍速档位(循环顺序)。 */
    val SPEED_STEPS = floatArrayOf(0.5f, 1.0f, 1.5f, 2.0f)

    /** 设置播放速率。 */
    fun setSpeed(speed: Float) {
        val v = speed.coerceIn(0.5f, 2.0f)
        AudioBridge.setSpeed(v)
        update { it.copy(speed = v) }
    }

    /** 循环切换倍速:0.5 → 1.0 → 1.5 → 2.0 → 0.5。 */
    fun cycleSpeed() {
        val cur = state.value.speed
        val next = SPEED_STEPS.firstOrNull { it > cur + 0.01f } ?: SPEED_STEPS.first()
        setSpeed(next)
    }

    /** seek 并乐观更新位置(引擎处理完会立即发精确位置事件校正)。 */
    fun seekTo(ms: Long) {
        val target = ms.coerceAtLeast(0)
        AudioBridge.seekMs(target)
        update { it.copy(positionMs = target) }
    }

    /**
     * 从引擎拉取权威状态,自愈后台期间丢失的事件(服务重建/Activity 重建/冻结窗口)。
     * UI 回前台(ON_RESUME)与服务启动时调用。
     */
    fun syncFromEngine() {
        val index = AudioBridge.queueIndex()
        val st = AudioBridge.state()
        update {
            it.copy(
                currentIndex = index,
                playing = st == PlayStates.PLAYING,
                positionMs = AudioBridge.positionMs(),
                durationMs = AudioBridge.durationMs(),
            )
        }
    }

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

    // ---- 播放记录:音乐/录音两域独立持久化(互不影响) ----

    /** 一个域的持久化队列(恢复时校验文件仍可读)。 */
    data class SavedQueue(
        val tracks: List<Track>,
        val index: Int,
        /** null = 全部音乐;录音域为 Recordings 目录路径。 */
        val source: String?,
    )

    /** 音乐域上次队列(全部音乐或某文件夹)。 */
    var musicCtx: SavedQueue? = null
        private set

    /** 录音域上次队列。 */
    var recCtx: SavedQueue? = null
        private set

    /** 上次活动域(true = 录音);冷启动据此恢复视图与队列。 */
    var lastDomainIsRecording: Boolean = false
        private set

    private var historyLoaded = false

    /** 服务启动时加载双域记录(含旧单域 KEY_PATHS 的一次性迁移)。 */
    fun loadHistory(context: Context) {
        if (historyLoaded) return
        historyLoaded = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        musicCtx = readQueue(prefs, "music_")
        recCtx = readQueue(prefs, "rec_")

        if (musicCtx == null && recCtx == null && prefs.getString(KEY_PATHS, null) != null) {
            // 旧单域记录迁移:按来源归类到对应域。
            val legacy = restore(context)
            if (legacy.isNotEmpty()) {
                val isRec = savedSource == Recorder.dir(context).absolutePath
                val sq = SavedQueue(legacy, savedIndex, savedSource)
                if (isRec) recCtx = sq else musicCtx = sq
                writeQueue(prefs.edit(), if (isRec) "rec_" else "music_", sq)
                    .putString(KEY_LAST_DOMAIN, if (isRec) "rec" else "music")
                    .apply()
            }
        }
        lastDomainIsRecording =
            prefs.getString(KEY_LAST_DOMAIN, null)?.let { it == "rec" } ?: (recCtx != null)
    }

    /** TrackStarted 时按当前队列所属域持久化(两域互不覆盖)。 */
    fun persist(context: Context) {
        val s = state.value
        if (s.tracks.isEmpty()) return
        val isRec = s.queueSource == Recorder.dir(context).absolutePath
        val sq = SavedQueue(s.tracks, s.currentIndex.coerceAtLeast(0), s.queueSource)
        writeQueue(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(),
            if (isRec) "rec_" else "music_",
            sq,
        ).putString(KEY_LAST_DOMAIN, if (isRec) "rec" else "music")
            .apply()
        if (isRec) recCtx = sq else musicCtx = sq
        lastDomainIsRecording = isRec
    }

    /** 冷启动恢复活动域队列(服务调用,暂停态)。 */
    fun restoreActiveDomain(context: Context) {
        val active = when {
            lastDomainIsRecording -> recCtx ?: musicCtx
            else -> musicCtx ?: recCtx
        } ?: return
        restoreQueue(active.tracks, active.index, active.source)
    }

    /**
     * 视图切域(音乐 ↔ 录音):暂停当前播放,载入目标域的上次队列(暂停态),
     * NowPlaying/高亮随之切换;目标域无记录则不动队列。两域记录互不影响。
     */
    fun swapDomain(context: Context, toRecording: Boolean) {
        val recPath = Recorder.dir(context).absolutePath
        val curIsRec = state.value.queueSource == recPath
        if (toRecording == curIsRec) return
        val target = (if (toRecording) recCtx else musicCtx) ?: return
        AudioBridge.pause()
        restoreQueue(target.tracks, target.index, target.source)
        lastDomainIsRecording = toRecording
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_DOMAIN, if (toRecording) "rec" else "music")
            .apply()
    }

    private fun readQueue(prefs: android.content.SharedPreferences, prefix: String): SavedQueue? {
        val paths = prefs.getString(prefix + "paths", null)?.split(SEP) ?: return null
        val titles = prefs.getString(prefix + "titles", null)?.split(SEP) ?: emptyList()
        val index = prefs.getInt(prefix + "index", 0)
        val source = prefs.getString(prefix + "source", "")?.takeIf { it.isNotBlank() }
        val tracks = paths.mapIndexedNotNull { i, path ->
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
        return if (tracks.isEmpty()) null else SavedQueue(tracks, index, source)
    }

    private fun writeQueue(
        ed: android.content.SharedPreferences.Editor,
        prefix: String,
        sq: SavedQueue,
    ): android.content.SharedPreferences.Editor = ed
        .putString(prefix + "paths", sq.tracks.joinToString(SEP) { it.path })
        .putString(prefix + "titles", sq.tracks.joinToString(SEP) { it.title })
        .putInt(prefix + "index", sq.index.coerceAtLeast(0))
        .putString(prefix + "source", sq.source ?: "")

    /** 旧单域记录读取(仅迁移用)。 */
    fun restore(context: Context): List<Track> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val paths = prefs.getString(KEY_PATHS, null)?.split(SEP) ?: return emptyList()
        val titles = prefs.getString(KEY_TITLES, null)?.split(SEP) ?: emptyList()
        savedIndex = prefs.getInt(KEY_INDEX, 0)
        savedSource = prefs.getString(KEY_SOURCE, "")?.takeIf { it.isNotBlank() }
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

package com.hirust.player

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hirust.player.audio.AudioBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * 曲库/播放页 ViewModel:MediaStore 扫描、文件夹播放列表(目录=列表)、命令透传。
 *
 * 状态直接来自 [PlayerBus](服务写入);本类不再持有引擎监听与音频焦点(均由 PlaybackService 接管)。
 */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    val state: StateFlow<BusState> get() = PlayerBus.state

    /** 全部曲库缓存(「全部音乐」视图用)。 */
    private var fullScan: List<Track> = emptyList()

    /** 引擎支持的音频扩展名(目录枚举过滤)。 */
    private val audioExts = setOf("mp3", "flac", "ogg", "oga", "wav", "m4a", "aac", "aif", "aiff")

    /** 扫描设备音乐库(MediaStore,AUDIO 权限已授予后调用);结果作为「全部音乐」视图。 */
    fun scan() {
        viewModelScope.launch(Dispatchers.IO) {
            val tracks = mediaStoreQuery(prefix = null)
            fullScan = tracks
            if (PlayerBus.state.value.viewSource == null) {
                PlayerBus.setView(tracks, null)
            }
        }
    }

    /** SAF 选中的目录 → 解析为真实路径 → 加入文件夹列表并打开该列表。 */
    fun addFolder(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val path = resolveDirPath(uri)
            val dir = path?.let(::File)
            if (dir == null || !dir.isDirectory) {
                PlayerBus.update { it.copy(error = "无法访问该目录(仅支持本机存储)") }
                return@launch
            }
            val folder = Folder(name = dir.name.ifEmpty { path }, path = dir.absolutePath)
            val merged = (PlayerBus.state.value.folders.filter { it.path != folder.path } + folder)
                .sortedBy { it.name.lowercase() }
            PlayerBus.setFolders(ctx, merged)
            openFolderInternal(folder)
        }
    }

    /** 切换视图:null = 全部音乐;否则打开对应文件夹列表。不影响正在播放的队列。 */
    fun openFolder(folder: Folder?) {
        viewModelScope.launch(Dispatchers.IO) {
            if (folder == null) {
                if (fullScan.isEmpty()) fullScan = mediaStoreQuery(prefix = null)
                PlayerBus.setView(fullScan, null)
            } else {
                openFolderInternal(folder)
            }
        }
    }

    private fun openFolderInternal(folder: Folder) {
        val tracks = loadFolderTracks(folder.path)
        if (tracks.isEmpty()) {
            PlayerBus.update { it.copy(error = "目录内没有可播放的音频:${folder.path}") }
        }
        PlayerBus.setView(tracks, folder.path)
    }

    /**
     * 文件夹列表 = 文件系统枚举(排序稳定)+ MediaStore 元数据合并;
     * 未入库文件的时长/标题经 Rust trackInfo 探测补全。
     */
    private fun loadFolderTracks(dirPath: String): List<Track> {
        val media = mediaStoreQuery(prefix = "$dirPath/").associateBy { it.path }
        val files = File(dirPath)
            .listFiles { f: File -> f.isFile && f.extension.lowercase() in audioExts }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        return files.map { f ->
            media[f.absolutePath] ?: Track(
                id = -1,
                title = f.nameWithoutExtension,
                artist = "",
                durationMs = 0,
                path = f.absolutePath,
            )
        }.map { t -> if (t.durationMs == 0L) enrichFromTags(t) else t }
    }

    /** Rust trackInfo(JSON)补全标题/艺人/时长;失败原样返回。 */
    private fun enrichFromTags(t: Track): Track = runCatching {
        val obj = JSONObject(AudioBridge.trackInfo(t.path))
        t.copy(
            title = obj.optString("title").ifEmpty { t.title },
            artist = obj.optString("artist"),
            durationMs = obj.optLong("durationMs"),
        )
    }.getOrDefault(t)

    /** MediaStore 音频查询;prefix 非空时按 DATA 路径前缀过滤(文件夹列表)。 */
    private fun mediaStoreQuery(prefix: String?): List<Track> {
        val ctx = getApplication<Application>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val minDurationMs = Settings.minDurationSec(ctx) * 1000L
        val selection = buildString {
            append("${MediaStore.Audio.Media.DURATION} >= ?")
            if (prefix != null) append(" AND ${MediaStore.Audio.Media.DATA} LIKE ?")
        }
        val args = if (prefix != null) {
            arrayOf(minDurationMs.toString(), "$prefix%")
        } else {
            arrayOf(minDurationMs.toString())
        }

        val tracks = mutableListOf<Track>()
        runCatching {
            ctx.contentResolver.query(
                collection,
                arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.ALBUM_ID,
                ),
                selection,
                args,
                "${MediaStore.Audio.Media.TITLE} ASC",
            )?.use { cursor ->
                val albumIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(4) ?: continue
                    tracks += Track(
                        id = cursor.getLong(0),
                        title = cursor.getString(1) ?: path.substringAfterLast('/'),
                        artist = cursor.getString(2) ?: "",
                        durationMs = cursor.getLong(3),
                        path = path,
                        albumId = if (albumIdx >= 0 && !cursor.isNull(albumIdx)) {
                            cursor.getLong(albumIdx)
                        } else {
                            null
                        },
                    )
                }
            }
        }
        return tracks
    }

    /** SAF 目录树 URI → 本机真实路径(仅支持 externalstorage;其他 provider 返回 null)。 */
    private fun resolveDirPath(treeUri: Uri): String? {
        if (treeUri.authority != "com.android.externalstorage.documents") return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return null
        val sep = docId.indexOf(':')
        if (sep < 0) return null
        val volume = docId.substring(0, sep)
        val rest = docId.substring(sep + 1)
        @Suppress("DEPRECATION")
        val base = if (volume == "primary") {
            Environment.getExternalStorageDirectory().path
        } else {
            "/storage/$volume"
        }
        return if (rest.isBlank()) base else "$base/$rest"
    }

    // ---- 命令透传 ----

    fun play() = AudioBridge.play()

    fun pause() = AudioBridge.pause()

    fun next() = AudioBridge.next()

    fun prev() = AudioBridge.prev()

    fun seekTo(ms: Long) = PlayerBus.seekTo(ms)

    /** 回前台对齐引擎状态(见 PlayerBus.syncFromEngine)。 */
    fun syncFromEngine() = PlayerBus.syncFromEngine()

    /**
     * 前台期间周期轮询引擎状态(2Hz):兜底事件链中断(服务重建/异常毒化等)。
     * 事件泵正常时事件仍主导实时性,轮询仅做校正,开销可忽略。
     */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                PlayerBus.syncFromEngine()
                kotlinx.coroutines.delay(500)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private var pollJob: kotlinx.coroutines.Job? = null

    /** 点播当前视图中的曲目。 */
    fun playAt(index: Int) = PlayerBus.playFromView(index)

    /** 循环模式:关 → 单曲 → 列表 → 关。 */
    fun toggleRepeat() {
        val next = when (PlayerBus.state.value.repeatMode) {
            com.hirust.player.audio.RepeatModes.OFF -> com.hirust.player.audio.RepeatModes.ONE
            com.hirust.player.audio.RepeatModes.ONE -> com.hirust.player.audio.RepeatModes.ALL
            else -> com.hirust.player.audio.RepeatModes.OFF
        }
        PlayerBus.setRepeatMode(next)
    }

    fun toggleShuffle() = PlayerBus.toggleShuffle()

    fun setVolume(volume: Float) = PlayerBus.setVolume(volume)
}

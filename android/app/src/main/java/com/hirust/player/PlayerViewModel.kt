package com.hirust.player

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hirust.player.audio.AudioBridge
import com.hirust.player.audio.EngineListener
import com.hirust.player.audio.PlayStates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** 播放页 UI 状态。 */
data class UiState(
    val trackTitles: List<String> = emptyList(),
    val currentIndex: Int = -1,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val lastError: String? = null,
)

/**
 * 播放页 ViewModel:持有队列文件、桥接 UI 与 Rust 引擎、处理音频焦点。
 *
 * Rust 事件到达时切回主线程更新 StateFlow(单一数据流,Composable 无副作用)。
 */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    /** 队列对应的本地缓存文件路径(与 trackTitles 同下标)。 */
    private val queuePaths = mutableListOf<String>()

    private val audioManager =
        app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var legacyFocusListener: AudioManager.OnAudioFocusChangeListener? = null

    private val engineListener = object : EngineListener {
        override fun onStateChanged(state: Int) = post {
            it.copy(playing = state == PlayStates.PLAYING)
        }

        override fun onPositionChanged(positionMs: Long) = post {
            it.copy(positionMs = positionMs)
        }

        override fun onTrackStarted(index: Int) = post {
            it.copy(currentIndex = index, positionMs = 0)
        }

        override fun onQueueEnded() = post {
            it.copy(playing = false, positionMs = 0, currentIndex = -1)
        }

        override fun onError(message: String) = post {
            it.copy(playing = false, lastError = message)
        }
    }

    init {
        AudioBridge.listener = engineListener
    }

    /** SAF 选中的音频复制到私有缓存后替换队列并从第一首播放。 */
    fun loadFromUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val dir = File(ctx.cacheDir, "playlist").apply { mkdirs() }
            // 清掉旧缓存,保持目录只服务当前队列
            dir.listFiles()?.forEach { it.delete() }

            val paths = ArrayList<String>(uris.size)
            val titles = ArrayList<String>(uris.size)
            for (uri in uris) {
                val title = displayName(uri) ?: "track ${paths.size + 1}"
                val dest = File(dir, "${paths.size}-${title}")
                val copied = runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    } != null
                }.getOrDefault(false)
                if (copied) {
                    paths += dest.absolutePath
                    titles += title
                }
            }
            if (paths.isEmpty()) {
                post { it.copy(lastError = "所选文件无法读取") }
                return@launch
            }
            queuePaths.clear()
            queuePaths.addAll(paths)
            _ui.value = _ui.value.copy(trackTitles = titles, currentIndex = -1, lastError = null)
            AudioBridge.setQueue(queuePaths.toTypedArray(), 0)
            AudioBridge.play()
        }
    }

    fun play() {
        requestFocus()
        AudioBridge.play()
    }

    fun pause() {
        AudioBridge.pause()
        abandonFocus()
    }

    fun next() = AudioBridge.next()

    fun prev() = AudioBridge.prev()

    fun seekTo(ms: Long) = AudioBridge.seekMs(ms)

    // ---- 音频焦点(M2 最简:永久丢失即暂停)----

    private fun requestFocus() {
        val loss = { pause() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS) loss()
                }
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            val listener = AudioManager.OnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS) loss()
            }
            legacyFocusListener = listener
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
    }

    private fun abandonFocus() {
        val req = focusRequest
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (req != null) audioManager.abandonAudioFocusRequest(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(legacyFocusListener)
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        val ctx = getApplication<Application>()
        ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()

    /** 在主线程应用一次状态变更。 */
    private fun post(reduce: (UiState) -> UiState) {
        viewModelScope.launch {
            _ui.value = reduce(_ui.value)
        }
    }

    override fun onCleared() {
        // 引擎与 .so 随进程存活(M3 引入服务生命周期);这里只断开监听并放弃焦点。
        AudioBridge.listener = null
        abandonFocus()
    }
}

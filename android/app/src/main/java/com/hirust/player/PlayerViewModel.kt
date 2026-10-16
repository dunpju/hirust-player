package com.hirust.player

import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hirust.player.audio.AudioBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 曲库/播放页 ViewModel:MediaStore 扫描 + 命令透传。
 *
 * 状态直接来自 [PlayerBus](服务写入);本类不再持有引擎监听与音频焦点
 * (均由 PlaybackService 接管,见 CLAUDE.md §3)。
 */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    val state: StateFlow<BusState> get() = PlayerBus.state

    /** 扫描设备音乐库(MediaStore,AUDIO 权限已授予后调用)。 */
    fun scan() {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
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
                    ),
                    "${MediaStore.Audio.Media.DURATION} >= 10000",
                    null,
                    "${MediaStore.Audio.Media.TITLE} ASC",
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val path = cursor.getString(4) ?: continue
                        tracks += Track(
                            id = cursor.getLong(0),
                            title = cursor.getString(1) ?: path.substringAfterLast('/'),
                            artist = cursor.getString(2) ?: "",
                            durationMs = cursor.getLong(3),
                            path = path,
                        )
                    }
                }
            }

            PlayerBus.setTracks(tracks)
            PlayerBus.persist(ctx)
        }
    }

    fun play() = AudioBridge.play()

    fun pause() = AudioBridge.pause()

    fun next() = AudioBridge.next()

    fun prev() = AudioBridge.prev()

    fun seekTo(ms: Long) = AudioBridge.seekMs(ms)

    fun playAt(index: Int) = PlayerBus.playIndex(index)
}

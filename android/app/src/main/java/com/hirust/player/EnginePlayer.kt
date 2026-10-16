package com.hirust.player

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.hirust.player.audio.AudioBridge

/**
 * 把 Rust 引擎包装成 media3 `Player`,供 MediaSession(通知/锁屏/耳机)使用。
 *
 * 仅实现控制面:命令转 `AudioBridge`,状态由 PlaybackService 收到引擎事件后
 * 调用 [onExternalStateChanged] 触发重建。Player.REPEAT_MODE_* 与
 * AudioBridge 的 RepeatModes 数值一致(0/1/2)。
 *
 * API 以 media3 **1.5.1** 实测为准(State.Builder / MediaItemData /
 * handleSetPlayWhenReady(Boolean) / handleSeek 三参)。
 */
@UnstableApi
class EnginePlayer : SimpleBasePlayer(Looper.getMainLooper()) {

    @Volatile private var playWhenReady = false

    /** 引擎事件到达后由服务调用,刷新会话状态(通知/锁屏)。 */
    fun onExternalStateChanged() {
        invalidateState()
    }

    override fun getState(): State {
        val s = PlayerBus.state.value
        val playlist = s.tracks.map { t ->
            SimpleBasePlayer.MediaItemData.Builder(t.path)
                .setMediaItem(t.asMediaItem())
                .setDurationUs(t.durationMs * 1000)
                .setIsSeekable(t.durationMs > 0)
                .build()
        }
        val index = if (s.currentIndex in playlist.indices) s.currentIndex else 0

        val commands = Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(Player.COMMAND_SET_REPEAT_MODE)
            .add(Player.COMMAND_SET_SHUFFLE_MODE)
            .build()

        return SimpleBasePlayer.State.Builder()
            .setAvailableCommands(commands)
            .setPlaybackState(if (playlist.isEmpty()) Player.STATE_IDLE else Player.STATE_READY)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(index)
            .setContentPositionMs(AudioBridge.positionMs())
            .setRepeatMode(s.repeatMode)
            .setShuffleModeEnabled(s.shuffle)
            .build()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (playWhenReady) AudioBridge.play() else AudioBridge.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            -> AudioBridge.prev()

            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            -> AudioBridge.next()

            else -> {
                if (mediaItemIndex != PlayerBus.state.value.currentIndex) {
                    PlayerBus.playIndex(mediaItemIndex)
                } else {
                    PlayerBus.seekTo(positionMs)
                }
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        PlayerBus.setRepeatMode(repeatMode)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        PlayerBus.setShuffle(shuffleModeEnabled)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    private fun Track.asMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(path)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist.ifEmpty { "未知艺人" })
                .setArtworkUri(albumArtUri)
                .build()
        )
        .build()
}

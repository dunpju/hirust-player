package com.hirust.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.hirust.player.audio.AudioBridge
import com.hirust.player.audio.EngineListener
import com.hirust.player.audio.PlayStates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 后台播放服务:持有 MediaSession(EnginePlayer)与引擎事件监听。
 *
 * - 引擎事件 → PlayerBus(UI 读)+ EnginePlayer.onExternalStateChanged(通知/锁屏);
 * - 音频焦点在此接管(永久丢失即暂停);
 * - 进程被杀后按持久化队列恢复(暂停态)。
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var enginePlayer: EnginePlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var focusRequest: AudioFocusRequest? = null
    private var legacyFocusListener: AudioManager.OnAudioFocusChangeListener? = null

    override fun onCreate() {
        super.onCreate()
        // 触发 AudioBridge 初始化(加载 .so + engineStart),进程内只发生一次。
        AudioBridge.version()

        restoreQueueIfNeeded()

        val player = EnginePlayer()
        val sessionActivity = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val session = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
        enginePlayer = player
        mediaSession = session

        AudioBridge.listener = object : EngineListener {
            override fun onStateChanged(state: Int) {
                PlayerBus.update { it.copy(playing = state == PlayStates.PLAYING) }
                if (state == PlayStates.PLAYING) ensureFocus() else abandonFocus()
            }

            override fun onPositionChanged(positionMs: Long) {
                PlayerBus.update { it.copy(positionMs = positionMs) }
            }

            override fun onTrackStarted(index: Int) {
                PlayerBus.update { it.copy(currentIndex = index, positionMs = 0) }
                PlayerBus.persist(this@PlaybackService)
            }

            override fun onTrackEnded(index: Int) = Unit

            override fun onQueueEnded() {
                PlayerBus.update { it.copy(playing = false, positionMs = 0, currentIndex = -1) }
                abandonFocus()
            }

            override fun onError(message: String) {
                PlayerBus.update { it.copy(playing = false, error = message) }
            }
        }

        // 统一状态失效:PlayerBus 任何变化(含 UI 侧改循环/音量)都刷新会话。
        serviceScope.launch {
            PlayerBus.state.collect { enginePlayer?.onExternalStateChanged() }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /** 播放中被划掉任务不停止(M3:后台连播);空闲时划掉则退出服务。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!PlayerBus.state.value.playing) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        AudioBridge.listener = null
        mediaSession?.release()
        mediaSession = null
        enginePlayer = null
        abandonFocus()
        super.onDestroy()
    }

    private fun restoreQueueIfNeeded() {
        if (PlayerBus.state.value.tracks.isNotEmpty()) return
        val restored = PlayerBus.restore(applicationContext)
        if (restored.isEmpty()) return
        PlayerBus.setTracks(restored)
        AudioBridge.setQueue(
            restored.map { it.path }.toTypedArray(),
            PlayerBus.savedIndex,
        )
    }

    // ---- 音频焦点(永久丢失即暂停;暂停/停止时放弃) ----

    private fun ensureFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest != null) return
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS) AudioBridge.pause()
                }
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            if (legacyFocusListener != null) return
            val listener = AudioManager.OnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS) AudioBridge.pause()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(legacyFocusListener)
            legacyFocusListener = null
        }
    }
}

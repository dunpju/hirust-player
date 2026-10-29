package com.hirust.player

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
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
    private var resumeOnFocusGain = false

    /** 耳机/蓝牙断开(AUDIO_BECOMING_NOISY)→ 暂停,避免外放。 */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                AudioBridge.pause()
            }
        }
    }

    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var focusRequest: AudioFocusRequest? = null
    private var legacyFocusListener: AudioManager.OnAudioFocusChangeListener? = null

    override fun onCreate() {
        super.onCreate()
        // 注入上下文(设置读取)+ 触发 AudioBridge 初始化(加载 .so + engineStart)。
        AudioBridge.init(this)
        AudioBridge.version()
        PlayerBus.restoreFolders(applicationContext)

        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        restoreQueueIfNeeded()

        val player = EnginePlayer()
        val sessionActivity = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = MediaSession.Builder(this, player)
        sessionActivity?.let { builder.setSessionActivity(it) }
        val session = builder.build()
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
                // 引擎先写入时长再发本事件,此刻查询即当前曲目时长(UI 进度条依赖)。
                val duration = AudioBridge.durationMs()
                PlayerBus.update {
                    it.copy(currentIndex = index, positionMs = 0, durationMs = duration)
                }
                PlayerBus.persist(this@PlaybackService)
            }

            override fun onTrackEnded(index: Int) = Unit

            override fun onQueueEnded() {
                PlayerBus.update {
                    it.copy(playing = false, positionMs = 0, durationMs = 0, currentIndex = -1)
                }
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

    /** 播放中被划掉任务不停止(M3:后台连播);空闲时划掉则退出服务。
     *  media3 1.5.1 该方法参数标注 @Nullable,必须以可空签名覆盖(javap 不可见注解,以实测为准)。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!PlayerBus.state.value.playing) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        runCatching { unregisterReceiver(noisyReceiver) }
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
        PlayerBus.restoreQueue(restored, PlayerBus.savedIndex, PlayerBus.savedSource)
        // 队列恢复属异步命令,同步一次引擎权威状态兜底(服务重启丢事件窗口)。
        PlayerBus.syncFromEngine()
    }

    // ---- 音频焦点(分级:永久丢失暂停 / 瞬时丢失可恢复 / 可压低降音量) ----

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                PlayerBus.setDucked(false)
                AudioBridge.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = true
                AudioBridge.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> PlayerBus.setDucked(true)
            AudioManager.AUDIOFOCUS_GAIN -> {
                PlayerBus.setDucked(false)
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    AudioBridge.play()
                }
            }
        }
    }

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
                .setOnAudioFocusChangeListener { change -> onFocusChange(change) }
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            if (legacyFocusListener != null) return
            val listener = AudioManager.OnAudioFocusChangeListener { change -> onFocusChange(change) }
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

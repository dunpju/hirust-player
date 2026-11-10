package com.hirust.player

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录音控制器:MediaRecorder(AAC/M4A)+ 独立心跳线程(计时/电平)。
 *
 * - 状态经 StateFlow 驱动 UI(覆盖层);计时在暂停段不计入;
 * - 录音存应用专属外部目录(MediaStore 不索引,不污染「全部音乐」);
 * - 分享走 FileProvider + 系统分享面板(微信以文件形式接收,无需 SDK)。
 */
object Recorder {

    /** 录音状态快照。 */
    data class State(
        val active: Boolean = false,
        val paused: Boolean = false,
        val elapsedMs: Long = 0,
        /** 最近一次电平(0..32767,0 表示暂停/未知)。 */
        val amplitude: Int = 0,
        val error: String? = null,
    )

    val state: StateFlow<State> = MutableStateFlow(State())

    /** 最近一次完成的录音文件(过短被丢弃时为 null)。 */
    var lastRecording: File? = null
        private set

    private var recorder: MediaRecorder? = null
    private var ticker: Thread? = null
    private var outputFile: File? = null
    private var accumulatedMs = 0L
    private var segmentStart = 0L

    /** 录音目录(应用专属外部存储,无需权限,不被媒体库索引)。 */
    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null), "Recordings").apply { mkdirs() }

    private fun update(f: (State) -> State) {
        (state as MutableStateFlow).value = f(state.value)
    }

    /** 开始录音(调用方须已获得 RECORD_AUDIO 权限)。 */
    fun start(context: Context) {
        if (state.value.active) return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir(context), "REC_$stamp.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        runCatching {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioChannels(1)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        }.onFailure { e ->
            runCatching { r.release() }
            update { State(error = "无法开始录音:${e.message}") }
            return
        }
        recorder = r
        outputFile = file
        accumulatedMs = 0
        segmentStart = SystemClock.elapsedRealtime()
        update { State(active = true) }
        startTicker()
    }

    /** 暂停/继续(计时排除暂停段)。 */
    fun togglePause() {
        val r = recorder ?: return
        if (state.value.paused) {
            runCatching { r.resume() }
                .onFailure { e -> update { it.copy(error = "继续失败:${e.message}") }; return }
            segmentStart = SystemClock.elapsedRealtime()
            update { it.copy(paused = false, amplitude = 0) }
        } else {
            runCatching { r.pause() }
                .onFailure { e -> update { it.copy(error = "暂停失败:${e.message}") }; return }
            accumulatedMs += SystemClock.elapsedRealtime() - segmentStart
            update { it.copy(paused = true, amplitude = 0) }
        }
    }

    /**
     * 停止并保存;返回录音文件(时长 <1s 视为误触,删除并返回 null)。
     */
    fun stop(): File? {
        val r = recorder ?: return null
        if (!state.value.paused) {
            accumulatedMs += SystemClock.elapsedRealtime() - segmentStart
        }
        val stopped = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        val file = outputFile
        recorder = null
        outputFile = null
        ticker?.let { t -> t.interrupt() }
        ticker = null

        val tooShort = !stopped || accumulatedMs < 1_000
        if (tooShort && file != null) file.delete()
        lastRecording = if (tooShort) null else file
        update { State(error = if (tooShort) "录音过短,已丢弃" else null) }
        return lastRecording
    }

    /** 心跳线程:100ms 刷新计时与电平(独立于 UI 生命周期,stop 时中断)。 */
    private fun startTicker() {
        ticker = Thread {
            while (!Thread.currentThread().isInterrupted) {
                val r = recorder ?: break
                if (!state.value.paused) {
                    val elapsed = accumulatedMs + (SystemClock.elapsedRealtime() - segmentStart)
                    val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
                    update { it.copy(elapsedMs = elapsed, amplitude = amp) }
                }
                Thread.sleep(100)
            }
        }.apply {
            name = "hirust-recorder-ticker"
            isDaemon = true
            start()
        }
    }

    /** 分享录音到系统面板(微信/QQ/蓝牙等;以文件形式发送)。 */
    fun share(context: Context, path: String) {
        val file = File(path)
        if (!file.isFile) return
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享录音"))
        }.onFailure { e ->
            update { it.copy(error = "分享失败:${e.message}") }
        }
    }
}

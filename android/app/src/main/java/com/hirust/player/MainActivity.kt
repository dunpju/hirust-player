package com.hirust.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.hirust.player.audio.AudioBridge
import com.hirust.player.audio.RepeatModes
import com.hirust.player.ui.HiRustTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startService(Intent(this, PlaybackService::class.java))
        startService(Intent(this, FloatButtonService::class.java))
        setContent {
            HiRustTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PlayerScreen()
                }
            }
        }
    }
}

private fun audioPermissions(): Array<String> {
    val audio = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        @Suppress("DEPRECATION")
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(audio, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(audio)
    }
}

@Composable
private fun PlayerScreen(vm: PlayerViewModel = viewModel()) {
    val ui by vm.state.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            vm.restoreLastView()
            vm.scan()
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(vm::addFolder) }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        val wanted = audioPermissions()
        val audioGranted = ContextCompat.checkSelfPermission(
            context,
            wanted.first(),
        ) == PackageManager.PERMISSION_GRANTED
        if (audioGranted) {
            vm.restoreLastView()
            vm.scan()
        } else {
            permLauncher.launch(wanted)
        }
    }

    // 前台期间与引擎保持对齐:回前台立即同步一次并开启 2Hz 轮询兜底
    // (后台切歌事件可能丢失:服务/Activity 重建、进程冻结窗口);切后台停止轮询。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    vm.startPolling()
                    vm.refreshRecordingsIfCurrent()
                    // 回前台:应用内 FAB 接管,桌面悬浮键隐藏。
                    FloatButtonController.hide()
                }
                Lifecycle.Event.ON_PAUSE -> vm.stopPolling()
                Lifecycle.Event.ON_STOP ->
                    // 退到桌面/其他应用:显示桌面悬浮键(需已授权,否则静默)。
                    FloatButtonController.show(context)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ---- 录音(全局悬浮 FAB + 覆盖层,设计见 docs/design-recording.md) ----
    val recordState by Recorder.state.collectAsState()
    val recordingsPath = remember(context) { Recorder.dir(context).absolutePath }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.startRecording()
        } else {
            Toast.makeText(context, "需要麦克风权限才能录音", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(recordState.error) {
        recordState.error?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
    Column(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("HiRust Player", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "v${AudioBridge.version()}",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { showSettings = true }) { Text("⚙") }
            }
        }

        PlaylistChips(
            folders = ui.folders,
            viewSource = ui.viewSource,
            recordingsPath = recordingsPath,
            onPick = vm::openFolder,
            onAdd = { folderPicker.launch(null) },
            onOpenRecordings = vm::openRecordings,
        )

        NowPlaying(track = ui.tracks.getOrNull(ui.currentIndex))

        SeekBar(
            positionMs = ui.positionMs,
            durationMs = ui.durationMs,
            onSeek = vm::seekTo,
        )

        // 播放三键靠左,右侧放倍速按钮(用户指定布局;单行不溢出)。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = vm::prev) { Text("⏮") }
            Button(onClick = { if (ui.playing) vm.pause() else vm.play() }) {
                Text(if (ui.playing) "⏸ 暂停" else "▶ 播放")
            }
            OutlinedButton(onClick = vm::next) { Text("⏭") }
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { vm.cycleSpeed() }) {
                Text("%.1f×".format(ui.speed))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = vm::toggleRepeat) {
                Text(
                    when (ui.repeatMode) {
                        RepeatModes.ONE -> "单曲"
                        RepeatModes.ALL -> "列表"
                        else -> "顺序"
                    }
                )
            }
            OutlinedButton(onClick = vm::toggleShuffle) {
                Text(if (ui.shuffle) "随机·开" else "随机·关")
            }
            Text("音量", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = ui.volume,
                onValueChange = vm::setVolume,
                modifier = Modifier.weight(1f),
            )
        }

        ui.error?.let {
            Text(
                text = "错误:$it",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        TrackList(
            tracks = ui.view,
            currentIndex = ui.currentIndex,
            highlightEnabled = ui.viewSource == ui.queueSource,
            playing = ui.playing,
            onPick = vm::playAt,
            shareEnabled = ui.viewSource == recordingsPath,
            onShare = { track -> Recorder.share(context, track.path) },
            deleteEnabled = ui.viewSource == recordingsPath,
            onDelete = { track -> vm.deleteRecording(track.path) },
            modifier = Modifier.weight(1f),
        )
    }

        // 全局悬浮录音键:所有视图可见;录音中隐藏(覆盖层接管)。
        if (!recordState.active) {
            FloatingActionButton(
                onClick = {
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        vm.startRecording()
                    } else {
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
            ) { Text("🎤") }
        }

        if (recordState.active) {
            RecordingOverlay(
                state = recordState,
                onTogglePause = vm::toggleRecordingPause,
                onStop = vm::stopRecording,
            )
        }
    }

    if (showSettings) {
        SettingsDialog(onDismiss = { showSettings = false }, onRescan = vm::scan)
    }
}

/** 播放列表切换条:「全部音乐」+ 各文件夹(目录即列表)+ 添加入口。 */
@Composable
private fun PlaylistChips(
    folders: List<Folder>,
    viewSource: String?,
    recordingsPath: String,
    onPick: (Folder?) -> Unit,
    onAdd: () -> Unit,
    onOpenRecordings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = viewSource == null,
            onClick = { onPick(null) },
            label = { Text("全部音乐") },
        )
        FilterChip(
            selected = viewSource == recordingsPath,
            onClick = onOpenRecordings,
            label = { Text("录音") },
        )
        folders.forEach { folder ->
            FilterChip(
                selected = viewSource == folder.path,
                onClick = { onPick(folder) },
                label = { Text(folder.name, maxLines = 1) },
            )
        }
        FilterChip(
            selected = false,
            onClick = onAdd,
            label = { Text("＋ 文件夹") },
        )
    }
}

/** 设置弹窗:输出缓冲(下一首生效)+ 短曲过滤(保存后立即重扫)。 */
@Composable
private fun SettingsDialog(onDismiss: () -> Unit, onRescan: () -> Unit) {
    val context = LocalContext.current
    var bufferMs by remember { mutableStateOf(Settings.bufferMs(context)) }
    var minDurationSec by remember { mutableStateOf(Settings.minDurationSec(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                Settings.setBufferMs(context, bufferMs)
                val durationChanged = minDurationSec != Settings.minDurationSec(context)
                Settings.setMinDurationSec(context, minDurationSec)
                if (durationChanged) onRescan()
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("输出缓冲:${bufferMs} ms(下一首生效)")
                Slider(
                    value = bufferMs.toFloat(),
                    onValueChange = { bufferMs = it.toInt() },
                    valueRange = 200f..1000f,
                    steps = 7,
                )
                Text("忽略短于 ${minDurationSec} 秒的音频(保存后重扫)")
                Slider(
                    value = minDurationSec.toFloat(),
                    onValueChange = { minDurationSec = it.toInt() },
                    valueRange = 0f..60f,
                )
                Text(
                    "深浅色跟随系统;Android 12+ 自动动态取色。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "桌面悬浮录音键",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(
                            SystemSettings.ACTION_MANAGE_OVERLAY_DATA,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }) {
                    Text(if (SystemSettings.canDrawOverlays(context)) "已授权" else "去授权")
                }
            }
        },
    )
}

/**
 * 左滑露出删除按钮的行容器(录音列表):按住右滑出红底删除键,
 * 松手按阈值吸附(半程以上停留,否则回弹);点删除执行回调并复位。
 */
@Composable
private fun SwipeRevealDelete(
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val density = LocalDensity.current
    val maxShift = with(density) { 76.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds(),
    ) {
        // 底层:红底删除按钮(左缘)
        Row(
            modifier = Modifier
                .matchParentSize()
                .background(Color(0xFFB3261E)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    scope.launch { offsetX.animateTo(0f) }
                    onDelete()
                },
                modifier = Modifier.padding(start = 12.dp),
            ) { Text("删除", color = Color.White) }
        }
        // 前层:行内容,横向拖动位移(0..maxShift),松手按阈值吸附。
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val target = (offsetX.value + dragAmount).coerceIn(0f, maxShift)
                            scope.launch { offsetX.snapTo(target) }
                        },
                        onDragEnd = {
                            scope.launch {
                                offsetX.animateTo(
                                    if (offsetX.value >= maxShift / 2) maxShift else 0f,
                                )
                            }
                        },
                    )
                },
        ) {
            content()
        }
    }
}

/** 正在播放条:封面(内嵌封面优先,MediaStore 专辑封面兜底)+ 标题/艺人。 */
@Composable
private fun NowPlaying(track: Track?) {
    val context = LocalContext.current
    var cover by remember(track?.path) { mutableStateOf<Any?>(null) }

    LaunchedEffect(track?.path) {
        cover = track?.let { t ->
            withContext(Dispatchers.IO) { CoverStore.load(context, t.path) } ?: t.albumArtUri
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = cover,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            contentScale = ContentScale.Crop,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track?.title ?: "未在播放",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = track?.artist?.ifEmpty { "未知艺人" } ?: "从曲库中选择一首开始",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TrackList(
    tracks: List<Track>,
    currentIndex: Int,
    highlightEnabled: Boolean,
    playing: Boolean,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    shareEnabled: Boolean = false,
    onShare: (Track) -> Unit = {},
    deleteEnabled: Boolean = false,
    onDelete: (Track) -> Unit = {},
) {
    if (tracks.isEmpty()) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("列表为空", style = MaterialTheme.typography.titleMedium)
            Text(
                "授予音频权限自动扫描曲库,或点「＋ 文件夹」把一个目录加为播放列表",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    // 当前曲目变化(含回前台自愈)时滚动到正在播放的曲目。
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex, highlightEnabled) {
        if (highlightEnabled && currentIndex in tracks.indices) {
            runCatching { listState.animateScrollToItem(currentIndex) }
        }
    }
    LazyColumn(modifier = modifier, state = listState) {
        itemsIndexed(tracks, key = { _, t -> t.path }) { index, track ->
            val current = highlightEnabled && index == currentIndex
            val rowContent: @Composable () -> Unit = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(index) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = track.albumArtUri,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    contentScale = ContentScale.Crop,
                )
                Text(
                    text = if (current && playing) "▶" else if (current) "⏸" else "",
                    color = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (current) FontWeight.Bold else null,
                        maxLines = 1,
                    )
                    Text(
                        text = "${track.artist.ifEmpty { "未知艺人" }} · ${formatMs(track.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (shareEnabled) {
                    TextButton(onClick = { onShare(track) }) { Text("分享") }
                }
            }
            }
            if (deleteEnabled) {
                SwipeRevealDelete(onDelete = { onDelete(track) }) { rowContent() }
            } else {
                rowContent()
            }
        }
    }
}

@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val fraction = if (durationMs > 0) {
        positionMs.coerceAtMost(durationMs).toFloat() / durationMs
    } else {
        0f
    }
    // 拖动中时间文本跟随手指;松手后由 PlayerBus 乐观更新接管(引擎事件随后校正)。
    val shownMs = if (dragging && durationMs > 0) (dragValue * durationMs).toLong() else positionMs

    Column {
        Slider(
            value = if (dragging) dragValue else fraction,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                dragging = false
                onSeek((dragValue * durationMs).toLong())
            },
            enabled = durationMs > 0,
        )
        Text(
            text = "${formatMs(shownMs)} / ${formatMs(durationMs)}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/** 录音中覆盖层:计时 + 24 根电平条 + 暂停/停止(拦截一切交互)。 */
@Composable
private fun RecordingOverlay(
    state: Recorder.State,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
) {
    // 电平历史:每 100ms(随 elapsedMs 变化)滚动追加一根。
    val bars = remember { mutableStateListOf<Float>() }
    LaunchedEffect(state.elapsedMs) {
        bars.add((state.amplitude / 32767f).coerceIn(0.06f, 1f))
        while (bars.size > 24) bars.removeAt(0)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = if (state.paused) "‖  已暂停" else "●  录音中",
                color = if (state.paused) Color.White else Color(0xFFFF7A59),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = formatMs(state.elapsedMs),
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.height(48.dp),
            ) {
                bars.forEach { level ->
                    Box(
                        modifier = Modifier
                            .width(5.dp)
                            .height((6 + level * 42).dp)
                            .background(
                                if (state.paused) Color.Gray else Color(0xFFFF7A59),
                            )
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onTogglePause) {
                    Text(if (state.paused) "▶ 继续" else "⏸ 暂停")
                }
                Button(onClick = onStop) { Text("⏹ 停止并保存") }
            }
        }
    }
}

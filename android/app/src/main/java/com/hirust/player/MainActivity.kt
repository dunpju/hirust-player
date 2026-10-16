package com.hirust.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startService(Intent(this, PlaybackService::class.java))
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
    ) { grants -> if (grants.values.any { it }) vm.scan() }

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
                Lifecycle.Event.ON_RESUME -> vm.startPolling()
                Lifecycle.Event.ON_PAUSE -> vm.stopPolling()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .safeDrawingPadding()
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
            onPick = vm::openFolder,
            onAdd = { folderPicker.launch(null) },
        )

        NowPlaying(track = ui.tracks.getOrNull(ui.currentIndex))

        SeekBar(
            positionMs = ui.positionMs,
            durationMs = ui.durationMs,
            onSeek = vm::seekTo,
        )

        // 播放三键独立一行,避免窄屏挤压(此前 5 键一行,末尾按钮被压成竖椭圆)。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            OutlinedButton(onClick = vm::prev) { Text("⏮") }
            Button(onClick = { if (ui.playing) vm.pause() else vm.play() }) {
                Text(if (ui.playing) "⏸ 暂停" else "▶ 播放")
            }
            OutlinedButton(onClick = vm::next) { Text("⏭") }
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
            modifier = Modifier.weight(1f),
        )
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
    onPick: (Folder?) -> Unit,
    onAdd: () -> Unit,
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
        },
    )
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

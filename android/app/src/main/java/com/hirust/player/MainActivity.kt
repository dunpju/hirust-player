package com.hirust.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hirust.player.audio.AudioBridge

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PlayerScreen()
                }
            }
        }
    }
}

@Composable
private fun PlayerScreen(vm: PlayerViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()

    Column(
        modifier = Modifier
            .safeDrawingPadding()
            .padding(24.dp)
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "HiRust Player",
            style = MaterialTheme.typography.headlineMedium,
        )

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris -> vm.loadFromUris(uris) }

        OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }) {
            Text("选择音频文件")
        }

        val title = ui.trackTitles.getOrNull(ui.currentIndex)
        Text(
            text = title ?: if (ui.trackTitles.isEmpty()) "队列为空" else "已停止",
            style = MaterialTheme.typography.titleMedium,
        )

        SeekBar(
            positionMs = ui.positionMs,
            durationMs = ui.durationMs,
            onSeek = vm::seekTo,
        )

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

        ui.lastError?.let {
            Text(
                text = "错误:$it",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Text(
            text = "队列 ${ui.trackTitles.size} 首 · Rust 引擎 ${AudioBridge.version()}",
            style = MaterialTheme.typography.bodySmall,
        )
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
            text = "${formatMs(positionMs)} / ${formatMs(durationMs)}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

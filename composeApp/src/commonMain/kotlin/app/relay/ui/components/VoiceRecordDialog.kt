// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import app.relay.core.nowMillis
import app.relay.generated.resources.Res
import app.relay.generated.resources.chat_send
import app.relay.generated.resources.chat_voice_permission_denied
import app.relay.generated.resources.chat_voice_record
import app.relay.generated.resources.chat_voice_recording
import app.relay.generated.resources.chat_voice_rerecord
import app.relay.generated.resources.ic_refresh
import app.relay.ui.theme.IconSizes
import org.jetbrains.compose.resources.StringResource
import app.relay.generated.resources.chat_voice_start
import app.relay.generated.resources.chat_voice_stop
import app.relay.generated.resources.chat_voice_too_short
import app.relay.generated.resources.common_cancel
import app.relay.generated.resources.file_play_audio
import app.relay.platform.RelayAudioPlayback
import app.relay.platform.RelayAudioRecording
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

import app.relay.ui.theme.LocalAppSpacing

private sealed interface RecordStage {
    data object Idle : RecordStage
    data class Recording(val path: String) : RecordStage
    data class Done(val path: String, val durationMs: Long) : RecordStage
}

@Composable
fun VoiceRecordDialog(
    cacheDir: String,
    onDismiss: () -> Unit,
    onSendVoice: (filePath: String, durationMs: Long) -> Unit,
) {
    var stage by remember { mutableStateOf<RecordStage>(RecordStage.Idle) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var errorRes by remember { mutableStateOf<StringResource?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            RelayAudioRecording.cancel()
            RelayAudioPlayback.stop()
        }
    }

    LaunchedEffect(stage) {
        if (stage is RecordStage.Recording) {
            elapsedSeconds = 0
            while (stage is RecordStage.Recording) {
                delay(1000L)
                elapsedSeconds++
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse,
        ),
    )

    // 骨架（底色 / 20dp 圆角 / 16sp·700 标题 / 描边按钮）见 `RelayDialog.kt`。
    RelayDialog(
        onDismissRequest = {
            RelayAudioRecording.cancel()
            RelayAudioPlayback.stop()
            onDismiss()
        },
        title = { Text(stringResource(Res.string.chat_voice_record)) },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = LocalAppSpacing.current.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when (val cur = stage) {
                    is RecordStage.Idle -> {
                        FilledIconButton(
                            onClick = {
                                errorRes = null
                                val path = "$cacheDir/voice_${nowMillis()}.m4a"
                                if (RelayAudioRecording.start(path)) {
                                    stage = RecordStage.Recording(path)
                                } else {
                                    errorRes = Res.string.chat_voice_permission_denied
                                }
                            },
                            modifier = Modifier.size(68.dp),
                        ) {
                            Icon(Icons.Outlined.Mic, contentDescription = null, modifier = Modifier.size(32.dp))
                        }
                        Spacer(Modifier.height(LocalAppSpacing.current.md))
                        Text(
                            text = stringResource(Res.string.chat_voice_start),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is RecordStage.Recording -> {
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .scale(pulseScale)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Mic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                        Spacer(Modifier.height(LocalAppSpacing.current.md))
                        val minutes = elapsedSeconds / 60
                        val seconds = elapsedSeconds % 60
                        val timeStr = "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
                        Text(
                            text = timeStr,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(LocalAppSpacing.current.xs))
                        Text(
                            text = stringResource(Res.string.chat_voice_recording),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(LocalAppSpacing.current.lg))
                        Button(
                            onClick = {
                                val duration = RelayAudioRecording.stop()
                                if (duration < 1000L) {
                                    errorRes = Res.string.chat_voice_too_short
                                    stage = RecordStage.Idle
                                } else {
                                    stage = RecordStage.Done(cur.path, duration)
                                }
                            },
                        ) {
                            Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(IconSizes.Body))
                            Text(stringResource(Res.string.chat_voice_stop), modifier = Modifier.padding(start = LocalAppSpacing.current.sm6))
                        }
                    }

                    is RecordStage.Done -> {
                        val durSec = (cur.durationMs / 1000L).coerceAtLeast(1L)
                        Text(
                            text = "${durSec}s",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(LocalAppSpacing.current.lg))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.lg),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                onClick = { RelayAudioPlayback.play(cur.path) },
                            ) {
                                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(IconSizes.Body))
                                Text(stringResource(Res.string.file_play_audio), modifier = Modifier.padding(start = LocalAppSpacing.current.xs))
                            }
                            AppOutlinedButton(
                                text = stringResource(Res.string.chat_voice_rerecord),
                                onClick = {
                                    RelayAudioPlayback.stop()
                                    stage = RecordStage.Idle
                                },
                                leadingIcon = Res.drawable.ic_refresh,
                            )
                        }
                    }
                }

                errorRes?.let { err ->
                    Spacer(Modifier.height(LocalAppSpacing.current.md))
                    Text(
                        text = stringResource(err),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (stage is RecordStage.Done) {
                RelayDialogButton(
                    text = stringResource(Res.string.chat_send),
                    primary = true,
                    onClick = {
                        val done = stage as RecordStage.Done
                        RelayAudioPlayback.stop()
                        onSendVoice(done.path, done.durationMs)
                        onDismiss()
                    },
                )
            }
        },
        dismissButton = {
            RelayDialogButton(
                text = stringResource(Res.string.common_cancel),
                onClick = {
                    RelayAudioRecording.cancel()
                    RelayAudioPlayback.stop()
                    onDismiss()
                },
            )
        },
    )
}

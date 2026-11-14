package com.example.ui.components

import android.media.MediaPlayer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.MessageEntity
import com.example.data.MessageStatus
import com.example.data.MessageType
import com.example.ui.theme.MeshAmber
import com.example.ui.theme.MeshBlue
import com.example.ui.theme.MeshGreen
import com.example.ui.theme.MeshSecondary
import com.example.ui.theme.MeshText
import com.example.util.FileStorageManager
import com.example.util.MediaUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(
    message: MessageEntity,
    showSender: Boolean,
    onStarToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    if (message.type == MessageType.SYSTEM) {
        Text(
            text = message.content,
            color = MeshSecondary,
            style = MaterialTheme.typography.labelSmall,
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        return
    }
    val mine = message.isOutgoing
    val bubbleColor = if (mine) MeshAmber else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (mine) Color(0xFF1A1C1E) else MeshText
    val secondary = if (mine) Color(0xFF5A4A3F) else MeshSecondary
    val shape = if (mine) {
        RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 5.dp)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start
    ) {
        if (showSender && !mine) {
            Text(
                text = message.senderName,
                color = MeshGreen,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
            )
        }
        Box(
            modifier = Modifier
                .widthIn(max = 330.dp)
                .clip(shape)
                .background(bubbleColor)
                .combinedClickable(
                    onClick = {
                        if (message.type == MessageType.IMAGE ||
                            message.type == MessageType.VIDEO ||
                            message.type == MessageType.FILE
                        ) {
                            // Opening is handled inside the content where a Context exists.
                        }
                    },
                    onLongClick = { onStarToggle(!message.isStarred) }
                )
                .padding(horizontal = 13.dp, vertical = 10.dp)
        ) {
            Column {
                when (message.type) {
                    MessageType.IMAGE -> ImageMessage(message)
                    MessageType.VOICE_NOTE -> VoiceMessage(message, mine, secondary)
                    MessageType.FILE, MessageType.VIDEO -> FileMessage(message, textColor, secondary)
                    else -> Text(
                        text = message.content,
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(5.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (message.isStarred) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = "Starred",
                            tint = if (mine) Color(0xFF8A6B1C) else MeshAmber,
                            modifier = Modifier
                                .size(12.dp)
                                .padding(end = 2.dp)
                        )
                    }
                    Text(
                        text = formatTime(message.timestamp),
                        color = secondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (mine) {
                        Spacer(Modifier.width(4.dp))
                        val icon = when (message.status) {
                            MessageStatus.READ -> Icons.Filled.DoneAll
                            MessageStatus.DELIVERED -> Icons.Filled.DoneAll
                            else -> Icons.Filled.Done
                        }
                        val tint = when (message.status) {
                            MessageStatus.READ -> MeshBlue
                            MessageStatus.DELIVERED -> MeshGreen
                            else -> secondary
                        }
                        Icon(
                            icon,
                            contentDescription = message.status.name,
                            tint = tint,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageMessage(message: MessageEntity) {
    val context = LocalContext.current
    val path = message.mediaPath
    if (path != null && File(path).exists()) {
        AsyncImage(
            model = File(path),
            contentDescription = message.content,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable {
                    MediaUtils.openFile(context, path, "image/*")
                },
            contentScale = ContentScale.Crop
        )
    } else {
        Text("Shared Image", color = MeshSecondary)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileMessage(message: MessageEntity, textColor: Color, secondary: Color) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable {
                MediaUtils.openFile(
                    context,
                    message.mediaPath,
                    FileStorageManager.mimeTypeFor(message.content)
                )
            }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(textColor.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.InsertDriveFile,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = message.content,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = MediaUtils.formatBytes(message.fileSize),
                color = secondary,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceMessage(message: MessageEntity, mine: Boolean, secondary: Color) {
    val path = message.mediaPath
    var playing by remember(message.id) { mutableStateOf(false) }
    var player by remember(message.id) { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(message.id) {
        onDispose {
            try { player?.release() } catch (_: Exception) {}
            player = null
        }
    }
    val tint = if (mine) Color(0xFF1A1C1E) else MeshAmber
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(min = 150.dp)
            .clip(RoundedCornerShape(50))
            .combinedClickable {
                if (path == null || !File(path).exists()) return@combinedClickable
                try {
                    if (playing) {
                        player?.pause()
                        playing = false
                    } else {
                        val current = player ?: MediaPlayer().apply {
                            setDataSource(path)
                            prepare()
                            setOnCompletionListener {
                                playing = false
                                seekTo(0)
                            }
                            player = this
                        }
                        current.start()
                        playing = true
                    }
                } catch (_: Exception) {
                    playing = false
                }
            }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = "Play Voice Note",
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(9.dp))
        Column {
            Text(
                text = "Voice Note",
                color = if (mine) Color(0xFF1A1C1E) else MeshText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (playing) "Playing..." else "Tap to play",
                color = secondary,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

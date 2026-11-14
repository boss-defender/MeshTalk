package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.MessageEntity
import com.example.data.MessageType
import com.example.ui.components.EmptyState
import com.example.ui.components.MeshCard
import com.example.ui.components.MessageRow
import com.example.ui.components.SectionLabel
import com.example.ui.theme.MeshAmber
import com.example.ui.theme.MeshBlue
import com.example.ui.theme.MeshRed
import com.example.ui.theme.MeshSecondary
import com.example.ui.theme.MeshText
import com.example.util.MediaUtils
import com.example.util.VoiceNoteRecorder
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun BroadcastHubScreen(
    messages: List<MessageEntity>,
    onBroadcastText: (String) -> Unit,
    onBroadcastFile: (File, MessageType) -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableIntStateOf(0) }
    val recorder = remember { VoiceNoteRecorder(context) }
    var micDenied by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micDenied = !granted
        if (granted) {
            isRecording = recorder.start() != null
            recordSeconds = 0
        }
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            MediaUtils.copyUriToCache(context, uri, "broadcast_file")?.let {
                onBroadcastFile(it, MessageType.FILE)
            }
        }
    }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LaunchedEffect(isRecording) {
        while (isRecording) {
            delay(1000)
            recordSeconds++
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MeshBlue.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.RssFeed, contentDescription = null, tint = MeshBlue)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    SectionLabel("Network Broadcast Hub")
                    Text(
                        "Broadcast Feed",
                        color = MeshText,
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            MeshCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "Posts, audio notes & files reach ALL connected devices on local Wi-Fi",
                        color = MeshSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    EmptyState(
                        title = "No broadcast messages yet",
                        body = "Be the first to post to the subnet!",
                        icon = Icons.Filled.RssFeed
                    )
                }
            }
            items(messages, key = { it.id }) { message ->
                MessageRow(
                    message = message,
                    showSender = true,
                    onStarToggle = {}
                )
            }
        }

        if (isRecording) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(MeshRed)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Recording Broadcast Voice Note... ${recordSeconds}s",
                    color = MeshRed,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    recorder.cancel()
                    isRecording = false
                    recordSeconds = 0
                }) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel Broadcast Voice Note")
                }
                IconButton(onClick = {
                    recorder.stop()?.let { onBroadcastFile(it, MessageType.VOICE_NOTE) }
                    isRecording = false
                    recordSeconds = 0
                }) {
                    Icon(Icons.Filled.Stop, contentDescription = "Send Broadcast Voice Note")
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Attach File", tint = MeshSecondary)
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            "Broadcast to all devices...",
                            color = MeshSecondary,
                            fontSize = 13.sp
                        )
                    },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MeshAmber.copy(alpha = 0.5f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                        focusedTextColor = MeshText,
                        unfocusedTextColor = MeshText,
                        cursorColor = MeshAmber
                    )
                )
                IconButton(onClick = {
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        isRecording = recorder.start() != null
                        recordSeconds = 0
                    } else {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }) {
                    Icon(Icons.Filled.Mic, contentDescription = "Voice Broadcast", tint = MeshSecondary)
                }
                IconButton(
                    enabled = input.isNotBlank(),
                    onClick = {
                        val text = input.trim()
                        if (text.isNotEmpty()) {
                            onBroadcastText(text)
                            input = ""
                        }
                    }
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Broadcast Send",
                        tint = if (input.isNotBlank()) MeshAmber else MeshSecondary
                    )
                }
            }
        }
        if (micDenied) {
            Text(
                "Microphone permission is required for voice broadcasts.",
                color = MeshRed,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }
}

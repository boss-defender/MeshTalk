package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.MessageEntity
import com.example.data.MessageType
import com.example.data.PeerEntity
import com.example.ui.components.EmptyState
import com.example.ui.components.InfoPill
import com.example.ui.components.MeshCard
import com.example.ui.components.MessageRow
import com.example.ui.components.PeerAvatar
import com.example.ui.components.SectionLabel
import com.example.ui.components.StatusDot
import com.example.ui.theme.MeshAmber
import com.example.ui.theme.MeshBlue
import com.example.ui.theme.MeshGreen
import com.example.ui.theme.MeshRed
import com.example.ui.theme.MeshSecondary
import com.example.ui.theme.MeshText
import com.example.util.MediaUtils
import com.example.util.VoiceNoteRecorder
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

@Composable
fun ChatsAndCallsScreen(
    myDisplayName: String,
    orderedPeers: List<PeerEntity>,
    messages: List<MessageEntity>,
    activePeerIp: String?,
    onSelectPeer: (String) -> Unit,
    onVideoCall: (String) -> Unit,
    onVoiceCall: (String) -> Unit,
    onSendMessage: (String, String) -> Unit,
    onSendFile: (String, File, MessageType) -> Unit,
    onConnectIp: (String) -> Unit,
    onBack: () -> Unit,
    onToggleStar: (String, Boolean) -> Unit
) {
    if (activePeerIp == null) {
        PeerListScreen(
            myDisplayName = myDisplayName,
            peers = orderedPeers,
            onSelectPeer = onSelectPeer,
            onConnectIp = onConnectIp
        )
    } else {
        val peer = orderedPeers.firstOrNull { it.ipAddress == activePeerIp }
        ChatDetailScreen(
            peerIp = activePeerIp,
            peer = peer,
            messages = messages,
            onVideoCall = onVideoCall,
            onVoiceCall = onVoiceCall,
            onSendMessage = onSendMessage,
            onSendFile = onSendFile,
            onBack = onBack,
            onToggleStar = onToggleStar
        )
    }
}

@Composable
private fun PeerListScreen(
    myDisplayName: String,
    peers: List<PeerEntity>,
    onSelectPeer: (String) -> Unit,
    onConnectIp: (String) -> Unit
) {
    var showConnectDialog by remember { mutableStateOf(false) }
    val onlineCount = peers.count { it.isOnline && !it.isUnreachable }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                SectionLabel("Discovered Mesh Devices & Chats")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Chats & Calls",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MeshText
                )
                Text(
                    text = "$onlineCount nearby · you are $myDisplayName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSecondary
                )
            }
            OutlinedButton(
                onClick = { showConnectDialog = true },
                shape = RoundedCornerShape(50),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MeshAmber.copy(alpha = 0.35f)
                )
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Connect", fontSize = 12.sp)
            }
        }

        if (peers.isEmpty()) {
            EmptyState(
                title = "No peers discovered yet",
                body = "Connect 2 Android phones to the same Wi-Fi router or hotspot. " +
                    "Auto-discovery will find nearby peers instantly."
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 6.dp,
                    bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(peers, key = { it.ipAddress }) { peer ->
                    PeerCard(peer = peer, onClick = { onSelectPeer(peer.ipAddress) })
                }
            }
        }
    }

    if (showConnectDialog) {
        ConnectIpDialog(
            onDismiss = { showConnectDialog = false },
            onConnect = {
                onConnectIp(it)
                showConnectDialog = false
            }
        )
    }
}

@Composable
private fun PeerCard(peer: PeerEntity, onClick: () -> Unit) {
    MeshCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PeerAvatar(
                name = peer.displayName,
                avatarColor = peer.avatarColor,
                online = peer.isOnline && !peer.isUnreachable
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = peer.displayName,
                        color = MeshText,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusDot(peer.isOnline, peer.isUnreachable)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "IP: ${peer.ipAddress}",
                    color = MeshSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
                val snippet = peer.lastMessageSnippet
                if (!snippet.isNullOrBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = snippet,
                        color = MeshSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (peer.unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(MeshAmber),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = peer.unreadCount.toString(),
                            color = Color(0xFF1A1C1E),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    InfoPill(
                        text = if (peer.isOnline && !peer.isUnreachable) "online" else "offline",
                        color = if (peer.isOnline && !peer.isUnreachable) MeshGreen else MeshSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatDetailScreen(
    peerIp: String,
    peer: PeerEntity?,
    messages: List<MessageEntity>,
    onVideoCall: (String) -> Unit,
    onVoiceCall: (String) -> Unit,
    onSendMessage: (String, String) -> Unit,
    onSendFile: (String, File, MessageType) -> Unit,
    onBack: () -> Unit,
    onToggleStar: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableIntStateOf(0) }
    val recorder = remember { VoiceNoteRecorder(context) }
    var permissionDenied by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionDenied = !granted
        if (granted) {
            val file = recorder.start()
            isRecording = file != null
            recordSeconds = 0
        }
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val file = MediaUtils.copyUriToCache(context, uri, "shared_file")
            if (file != null) {
                onSendFile(peerIp, file, MessageType.FILE)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { recorder.cancel() }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LaunchedEffect(isRecording) {
        while (isRecording) {
            delay(1000)
            recordSeconds++
        }
    }
    val displayName = peer?.displayName ?: "Peer-$peerIp"
    val online = peer?.isOnline == true && peer?.isUnreachable != true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to list",
                    tint = MeshText
                )
            }
            PeerAvatar(
                name = displayName,
                avatarColor = peer?.avatarColor ?: 0,
                size = 40.dp,
                online = online
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    color = MeshText,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(online, peer?.isUnreachable == true)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = when {
                            online && (peer?.rttMs ?: -1) >= 0 -> "IP: $peerIp · ${peer?.rttMs} ms"
                            online -> "IP: $peerIp"
                            else -> "IP: $peerIp · offline"
                        },
                        color = if (online) MeshGreen else MeshSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            IconButton(onClick = { onVoiceCall(peerIp) }) {
                Icon(Icons.Filled.Call, contentDescription = "Voice Call", tint = MeshGreen)
            }
            IconButton(onClick = { onVideoCall(peerIp) }) {
                Icon(Icons.Filled.Videocam, contentDescription = "Video Call", tint = MeshBlue)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 14.dp,
                vertical = 10.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    EmptyState(
                        title = "Secure direct link",
                        body = "Messages travel directly over local Wi-Fi. No internet needed.",
                        icon = Icons.Filled.Call
                    )
                }
            }
            items(messages, key = { it.id }) { message ->
                MessageRow(
                    message = message,
                    showSender = false,
                    onStarToggle = { onToggleStar(message.id, it) }
                )
            }
        }

        if (isRecording) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MeshRed)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Recording Voice Note... ${recordSeconds}s",
                    color = MeshRed,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    recorder.cancel()
                    isRecording = false
                    recordSeconds = 0
                }) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = MeshSecondary)
                }
                IconButton(onClick = {
                    val file = recorder.stop()
                    isRecording = false
                    recordSeconds = 0
                    if (file != null) {
                        onSendFile(peerIp, file, MessageType.VOICE_NOTE)
                    }
                }) {
                    Icon(Icons.Filled.Stop, contentDescription = "Send Voice Note", tint = MeshAmber)
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
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Attach File",
                        tint = MeshSecondary
                    )
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            "Type message over Wi-Fi...",
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
                        val file = recorder.start()
                        isRecording = file != null
                        recordSeconds = 0
                    } else {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }) {
                    Icon(Icons.Filled.Mic, contentDescription = "Send Voice Note", tint = MeshSecondary)
                }
                IconButton(
                    onClick = {
                        val text = input.trim()
                        if (text.isNotEmpty()) {
                            onSendMessage(peerIp, text)
                            input = ""
                        }
                    },
                    enabled = input.isNotBlank()
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (input.isNotBlank()) MeshAmber else MeshSecondary
                    )
                }
            }
        }
        if (permissionDenied) {
            Text(
                "Microphone permission is required for voice notes.",
                color = MeshRed,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun ConnectIpDialog(onDismiss: () -> Unit, onConnect: (String) -> Unit) {
    var ip by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect IP") },
        text = {
            Column {
                Text(
                    "Enter the local IP of another MeshTalk device on your Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSecondary
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    singleLine = true,
                    placeholder = { Text("192.168.1.42") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConnect(ip.trim()) },
                enabled = ip.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = MeshAmber)
            ) {
                Text("Connect", color = Color(0xFF1A1C1E))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

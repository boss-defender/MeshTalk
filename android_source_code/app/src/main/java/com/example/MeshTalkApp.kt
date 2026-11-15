package com.example

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.MessageEntity
import com.example.ui.MainViewModel
import com.example.ui.components.MeshBottomBar
import com.example.ui.components.MeshTopBar
import com.example.ui.screens.ActiveCallScreen
import com.example.ui.screens.BroadcastHubScreen
import com.example.ui.screens.ChatsAndCallsScreen
import com.example.ui.screens.IncomingCallOverlay
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.WalkieTalkieScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.flowOf

@Composable
fun MeshTalkApp(viewModel: MainViewModel) {
    var isOledTheme by rememberSaveable { mutableStateOf(false) }
    MyApplicationTheme(isOledTheme = isOledTheme) {
        MeshTalkRoot(
            viewModel = viewModel,
            isOledTheme = isOledTheme,
            onToggleTheme = { isOledTheme = !isOledTheme }
        )
    }
}

@Composable
private fun MeshTalkRoot(
    viewModel: MainViewModel,
    isOledTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var currentTab by rememberSaveable { mutableStateOf(NavTab.CHATS_AND_CALLS) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showEditNameDialog by rememberSaveable { mutableStateOf(false) }
    var activePeerIp by rememberSaveable { mutableStateOf<String?>(null) }
    var nameInput by remember { mutableStateOf("") }

    val myDisplayName by viewModel.myDisplayName.collectAsStateWithLifecycle()
    val autoDelete by viewModel.autoDeleteOldMessages.collectAsStateWithLifecycle()
    val currentChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val localIp by viewModel.localIpAddress.collectAsStateWithLifecycle()
    val wifiSsid by viewModel.wifiSsid.collectAsStateWithLifecycle()
    val discoveredPeers by viewModel.discoveredPeers.collectAsStateWithLifecycle()
    val orderedPeers by viewModel.orderedPeers.collectAsStateWithLifecycle()
    val dbPeers by viewModel.dbPeers.collectAsStateWithLifecycle()
    val globalMessages by viewModel.globalMessages.collectAsStateWithLifecycle()
    val p2pStatus by viewModel.p2pConnectionStatus.collectAsStateWithLifecycle()
    val incomingCall by viewModel.incomingCall.collectAsStateWithLifecycle()
    val activeCall by viewModel.activeCall.collectAsStateWithLifecycle()
    val remoteVideoBitmap by viewModel.remoteVideoBitmap.collectAsStateWithLifecycle()
    val isMicMuted by viewModel.isMicMuted.collectAsStateWithLifecycle()
    val isVideoEnabled by viewModel.isVideoEnabled.collectAsStateWithLifecycle()
    val isSpeakerOn by viewModel.isSpeakerOn.collectAsStateWithLifecycle()
    val isTransmitting by viewModel.isTransmitting.collectAsStateWithLifecycle()
    val isReceiving by viewModel.isReceiving.collectAsStateWithLifecycle()
    val isListeningEnabled by viewModel.isListeningEnabled.collectAsStateWithLifecycle()
    val talkingPeer by viewModel.talkingPeer.collectAsStateWithLifecycle()
    val audioAmplitude by viewModel.audioAmplitude.collectAsStateWithLifecycle()
    val audioWaveform by viewModel.audioWaveform.collectAsStateWithLifecycle()

    val directMessagesFlow = remember(activePeerIp) {
        activePeerIp?.let { viewModel.messagesForChat(it) } ?: flowOf(emptyList<MessageEntity>())
    }
    val directMessages by directMessagesFlow.collectAsStateWithLifecycle(
        initialValue = emptyList()
    )

    LaunchedEffect(Unit) {
        viewModel.toasts.collect { message -> snackbarHostState.showSnackbar(message) }
    }
    LaunchedEffect(activePeerIp, directMessages.size) {
        activePeerIp?.let { viewModel.markChatAsRead(it) }
    }
    LaunchedEffect(discoveredPeers) {
        // Keep the visible chat list fresh; Room already persists peers for offline chats.
    }

    if (activeCall != null) {
        ActiveCallScreen(
            callOffer = activeCall!!,
            callEngine = viewModel.localCallEngine,
            remoteVideoBitmap = remoteVideoBitmap,
            isMicMuted = isMicMuted,
            isVideoEnabled = isVideoEnabled,
            isSpeakerOn = isSpeakerOn,
            onEndCall = { viewModel.endCurrentCall() }
        )
        return
    }

    val onlineCount = discoveredPeers.values.count { it.isOnline && !it.isUnreachable }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (!showSettings) {
                MeshTopBar(
                    status = p2pStatus,
                    peerCount = onlineCount,
                    onEditName = {
                        nameInput = myDisplayName
                        showEditNameDialog = true
                    },
                    onOpenSettings = { showSettings = true }
                )
            }
        },
        bottomBar = {
            if (!showSettings) {
                MeshBottomBar(current = currentTab, onSelect = { currentTab = it })
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        if (showSettings) {
            SettingsScreen(
                currentDisplayName = myDisplayName,
                deviceId = viewModel.repository.myDeviceId,
                localIp = localIp,
                wifiSsid = wifiSsid,
                status = p2pStatus,
                peerCount = onlineCount,
                currentChannel = currentChannel,
                autoDeleteOldMessages = autoDelete,
                isOledTheme = isOledTheme,
                onSaveDisplayName = { viewModel.updateDisplayName(it) },
                onChannelChange = { viewModel.setChannel(it) },
                onAutoDeleteChange = { viewModel.setAutoDeleteOldMessages(it) },
                onCleanUp = { viewModel.cleanUpOldMessages() },
                onToggleTheme = onToggleTheme,
                onBack = { showSettings = false }
            )
        } else {
            val contentModifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
            when (currentTab) {
                NavTab.CHATS_AND_CALLS -> ChatsAndCallsScreen(
                    myDisplayName = myDisplayName,
                    orderedPeers = orderedPeers,
                    messages = directMessages,
                    activePeerIp = activePeerIp,
                    onSelectPeer = { activePeerIp = it },
                    onVideoCall = { viewModel.initiateCall(it, true) },
                    onVoiceCall = { viewModel.initiateCall(it, false) },
                    onSendMessage = { chat, text -> viewModel.sendTextMessage(chat, text) },
                    onSendFile = { chat, file, type -> viewModel.sendFile(chat, file, type) },
                    onConnectIp = { viewModel.connectToIp(it) },
                    onBack = { activePeerIp = null },
                    onToggleStar = { id, starred -> viewModel.toggleMessageStar(id, starred) }
                )
                NavTab.BROADCAST_HUB -> BroadcastHubScreen(
                    messages = globalMessages,
                    onBroadcastText = { viewModel.sendTextMessage("GLOBAL", it) },
                    onBroadcastFile = { file, type -> viewModel.sendFile("GLOBAL", file, type) }
                )
                NavTab.WALKIE_TALKIE -> WalkieTalkieScreen(
                    channel = currentChannel,
                    isTransmitting = isTransmitting,
                    isReceiving = isReceiving,
                    isListeningEnabled = isListeningEnabled,
                    talkingPeer = talkingPeer,
                    audioAmplitude = audioAmplitude,
                    audioWaveform = audioWaveform,
                    onChannelChange = { viewModel.setChannel(it) },
                    onListeningChange = { viewModel.setListeningEnabled(it) },
                    onStartTransmitting = { viewModel.startTransmitting() },
                    onStopTransmitting = { viewModel.stopTransmitting() }
                )
            }
        }
    }

    if (incomingCall != null) {
        IncomingCallOverlay(
            callOffer = incomingCall!!,
            onAccept = { viewModel.acceptIncomingCall() },
            onDecline = { viewModel.declineIncomingCall() }
        )
    }

    if (showEditNameDialog) {
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            title = { Text("Change Your Device Name") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateDisplayName(nameInput)
                        showEditNameDialog = false
                    },
                    enabled = nameInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Save Name", color = Color(0xFF1A1C1E))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditNameDialog = false }) { Text("Cancel") }
            }
        )
    }
}

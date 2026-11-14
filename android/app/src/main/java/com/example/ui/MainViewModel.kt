package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.MessageEntity
import com.example.data.MessageType
import com.example.data.PeerEntity
import com.example.repository.CallOffer
import com.example.repository.P2PConnectionStatus
import com.example.repository.P2PRepository
import com.example.service.WalkieTalkieService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val repository = P2PRepository(application)

    val myDisplayName: StateFlow<String> = repository.myDisplayName
    val autoDeleteOldMessages: StateFlow<Boolean> = repository.autoDeleteOldMessages
    val currentChannel: StateFlow<Int> = repository.currentChannel
    val localIpAddress: StateFlow<String> = repository.localIpAddress
    val wifiSsid: StateFlow<String> = repository.wifiSsid
    val discoveredPeers: StateFlow<Map<String, PeerEntity>> = repository.discoveredPeers
    val p2pConnectionStatus: StateFlow<P2PConnectionStatus> = repository.p2pConnectionStatus
    val incomingCall: StateFlow<CallOffer?> = repository.incomingCall
    val activeCall: StateFlow<CallOffer?> = repository.activeCall
    val localCallEngine = repository.localCallEngine
    val remoteVideoBitmap: StateFlow<Bitmap?> = repository.localCallEngine.remoteVideoBitmap
    val isMicMuted = repository.localCallEngine.isMicMuted
    val isVideoEnabled = repository.localCallEngine.isVideoEnabled
    val isSpeakerOn = repository.localCallEngine.isSpeakerPhoneOn

    val isTransmitting = repository.udpAudioEngine.isTransmitting
    val isReceiving = repository.udpAudioEngine.isReceiving
    val isListeningEnabled = repository.udpAudioEngine.isListeningEnabled
    val talkingPeer = repository.udpAudioEngine.talkingPeer
    val audioAmplitude = repository.udpAudioEngine.audioAmplitude
    val audioWaveform = repository.udpAudioEngine.audioWaveform

    val orderedPeers: StateFlow<List<PeerEntity>> = repository.orderedDiscoveredPeers.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )
    val dbPeers: StateFlow<List<PeerEntity>> = repository.allDbPeers.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )
    val globalMessages: StateFlow<List<MessageEntity>> = repository.getGlobalMessages().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )
    val toasts = repository.toasts
    val transferProgress = repository.transferProgress

    private val _isRecordingVoice = MutableStateFlow(false)
    val isRecordingVoice: StateFlow<Boolean> = _isRecordingVoice.asStateFlow()

    init {
        WalkieTalkieService.attachRepository(repository)
        WalkieTalkieService.start(application)
    }

    fun messagesForChat(chatId: String): Flow<List<MessageEntity>> =
        repository.getMessagesForChat(chatId)

    fun peerForChat(chatId: String): PeerEntity? = repository.peerForChat(chatId)

    fun sendTextMessage(targetChatId: String, content: String) {
        repository.sendTextMessage(targetChatId, content)
    }

    fun sendFile(targetChatId: String, file: File, type: MessageType) {
        repository.sendMediaFile(targetChatId, file, type)
    }

    fun sendUri(targetChatId: String, uri: Uri, type: MessageType = MessageType.FILE) {
        viewModelScope.launch {
            val file = repository.storeIncomingUri(
                uri,
                uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
            )
            if (file != null) repository.sendMediaFile(targetChatId, file, type)
        }
    }

    fun updateDisplayName(name: String) = repository.updateDisplayName(name)
    fun setChannel(channel: Int) = repository.setChannel(channel)
    fun startTransmitting() = repository.startTransmitting()
    fun stopTransmitting() = repository.stopTransmitting()
    fun setListeningEnabled(enabled: Boolean) = repository.setListeningEnabled(enabled)
    fun markChatAsRead(chatId: String) = repository.markChatAsRead(chatId)
    fun toggleMessageStar(messageId: String, isStarred: Boolean) =
        repository.toggleMessageStar(messageId, isStarred)
    fun initiateCall(targetPeerIp: String, isVideoCall: Boolean) =
        repository.initiateCall(targetPeerIp, isVideoCall)
    fun acceptIncomingCall() = repository.acceptIncomingCall()
    fun declineIncomingCall() = repository.declineIncomingCall()
    fun endCurrentCall() = repository.endCurrentCall()
    fun connectToIp(ip: String) = repository.connectToIp(ip)
    fun setAutoDeleteOldMessages(enabled: Boolean) =
        repository.setAutoDeleteOldMessages(enabled)
    fun cleanUpOldMessages() = repository.cleanUpOldMessagesNow()

    fun newVoiceNoteFile(): File {
        val dir = File(getApplication<Application>().cacheDir, "voice_notes").apply { mkdirs() }
        return File(dir, "VN_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}.m4a")
    }

    override fun onCleared() {
        super.onCleared()
        // The foreground service keeps the mesh alive; release only UI-scoped state.
        repository.localCallEngine.endCall()
    }
}

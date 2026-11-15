package com.example.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.ChatDao
import com.example.data.MessageEntity
import com.example.data.MessageStatus
import com.example.data.MessageType
import com.example.data.PeerEntity
import com.example.network.CHANNEL_PREFIX
import com.example.network.DISCOVERY_INTERVAL_MS
import com.example.network.FileProgress
import com.example.network.GLOBAL_CHAT
import com.example.network.IceCandidate
import com.example.network.IncomingFile
import com.example.network.LocalCallEngine
import com.example.network.MulticastLockManager
import com.example.network.NetworkUtils
import com.example.network.NsdHelper
import com.example.network.P2PPacket
import com.example.network.P2PServer
import com.example.network.PeerConnectionObserver
import com.example.network.PttEngine
import com.example.network.SignalType
import com.example.network.TCP_PORT
import com.example.network.inferMessageType
import com.example.network.snippetForType
import com.example.util.FileStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Application-level owner of discovery, chats, file transfer, PTT and calls.
 * Mirrors the original P2PRepository API and Room persistence model.
 */
class P2PRepository(private val context: Context) {
    companion object {
        private const val TAG = "P2PRepository"
        const val SERVER_PORT = TCP_PORT
        private const val CHANNEL_PREFIX_VALUE = CHANNEL_PREFIX
        private const val TWO_DAYS_MS = 2L * 24 * 60 * 60 * 1000
        private const val UNREACHABLE_MS = 45_000L
        private const val OFFLINE_MS = 120_000L
    }

    private val database: AppDatabase = AppDatabase.getDatabase(context)
    private val chatDao: ChatDao = database.chatDao()
    private val prefs: SharedPreferences =
        context.getSharedPreferences("walkie_prefs", Context.MODE_PRIVATE)

    val myDeviceId: String = "${Build.MODEL}_${UUID.randomUUID().toString().take(4)}"
    private val _myDisplayName = MutableStateFlow(generateDefaultName())
    val myDisplayName: StateFlow<String> = _myDisplayName.asStateFlow()
    val autoDeleteOldMessages = MutableStateFlow(prefs.getBoolean("auto_delete_old_messages", true))
    private val _currentChannel = MutableStateFlow(prefs.getInt("current_channel", 1).coerceIn(1, 8))
    val currentChannel: StateFlow<Int> = _currentChannel.asStateFlow()
    val localIpAddress = MutableStateFlow(NetworkUtils.getLocalIpAddress(context))
    val wifiSsid = MutableStateFlow(NetworkUtils.getWifiSsid(context))
    val multicastLockManager = MulticastLockManager(context)
    val udpAudioEngine = PttEngine(context)
    val localCallEngine = LocalCallEngine(context)

    private val peerMap = ConcurrentHashMap<String, PeerEntity>()
    private val _discoveredPeers = MutableStateFlow<Map<String, PeerEntity>>(emptyMap())
    val discoveredPeers: StateFlow<Map<String, PeerEntity>> = _discoveredPeers.asStateFlow()
    val allDbPeers: Flow<List<PeerEntity>> = chatDao.getAllPeers()

    val orderedDiscoveredPeers: Flow<List<PeerEntity>> = combine(
        _discoveredPeers,
        allDbPeers
    ) { live, db ->
        val merged = LinkedHashMap<String, PeerEntity>()
        for (peer in db) merged[peer.ipAddress] = peer
        for ((ip, peer) in live) merged[ip] = peer
        merged.values.sortedByDescending { maxOf(it.lastSeen, it.lastMessageTime) }
    }

    private val _incomingCall = MutableStateFlow<CallOffer?>(null)
    val incomingCall: StateFlow<CallOffer?> = _incomingCall.asStateFlow()
    private val _activeCall = MutableStateFlow<CallOffer?>(null)
    val activeCall: StateFlow<CallOffer?> = _activeCall.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val toasts = _toasts.asSharedFlow()
    private val _transferProgress = MutableSharedFlow<FileProgress>(extraBufferCapacity = 64)
    val transferProgress = _transferProgress.asSharedFlow()

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var p2pServer: P2PServer? = null
    private var nsdHelper: NsdHelper? = null
    private var discoveryJob: Job? = null
    private var maintenanceJob: Job? = null
    private var cleanupJob: Job? = null
    private var lanScanJob: Job? = null
    private var started = false

    val p2pConnectionStatus: StateFlow<P2PConnectionStatus> = combine(
        localIpAddress,
        _discoveredPeers
    ) { ip, peers ->
        if (ip.isBlank() || ip == "127.0.0.1" || ip == "0.0.0.0") {
            P2PConnectionStatus.DISCONNECTED
        } else if (peers.values.any { it.isOnline && !it.isUnreachable }) {
            P2PConnectionStatus.CONNECTED
        } else {
            P2PConnectionStatus.SEARCHING
        }
    }.stateIn(repositoryScope, SharingStarted.Eagerly, P2PConnectionStatus.SEARCHING)

    init {
        localCallEngine.setObserver(object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: IceCandidate) {
                val call = _activeCall.value ?: _incomingCall.value ?: return
                sendCallPacket(SignalType.ICE_CANDIDATE, call.callerIp, candidate.candidate)
            }

            override fun onSdpOffer(sdp: String) {
                val call = _activeCall.value ?: _incomingCall.value ?: return
                sendCallPacket(SignalType.SDP_OFFER, call.callerIp, sdp)
            }

            override fun onSdpAnswer(sdp: String) {
                val call = _activeCall.value ?: _incomingCall.value ?: return
                sendCallPacket(SignalType.SDP_ANSWER, call.callerIp, sdp)
            }

            override fun onConnectionStateChange(isConnected: Boolean) {
                Log.d(TAG, "Call connection state: $isConnected")
            }
        })
        startAutoCleanupTask()
    }

    private fun generateDefaultName(): String {
        val saved = prefs.getString("custom_display_name", null)
        if (!saved.isNullOrBlank()) return saved
        val suffix = myDeviceId.takeLast(4).uppercase(Locale.ROOT)
        return "Walkie-User-$suffix"
    }

    fun startP2PServices() {
        if (started) return
        started = true
        multicastLockManager.acquire()
        localIpAddress.value = NetworkUtils.getLocalIpAddress(context)
        wifiSsid.value = NetworkUtils.getWifiSsid(context)

        p2pServer = P2PServer(
            context = context,
            selfId = { myDeviceId },
            onPacket = { packet, senderIp -> handleIncomingPacket(packet, senderIp) },
            onFileReceived = { info -> handleIncomingFile(info) },
            onProgress = { progress -> _transferProgress.tryEmit(progress) },
            onError = { message -> _toasts.tryEmit(message) }
        ).also { it.start() }

        nsdHelper = NsdHelper(context) { name, host, port ->
            onNsdPeer(name, host, port)
        }.also {
            it.registerService(_myDisplayName.value)
            it.startDiscovery()
        }

        udpAudioEngine.startListening()

        discoveryJob = repositoryScope.launch {
            while (isActive) {
                val ping = P2PPacket(
                    type = SignalType.PING,
                    senderId = myDeviceId,
                    senderName = _myDisplayName.value,
                    targetId = GLOBAL_CHAT,
                    payload = System.currentTimeMillis().toString()
                )
                p2pServer?.sendUdp(ping)
                delay(DISCOVERY_INTERVAL_MS)
            }
        }

        maintenanceJob = repositoryScope.launch {
            while (isActive) {
                delay(10_000)
                expireStalePeers()
            }
        }

        lanScanJob = repositoryScope.launch {
            while (isActive) {
                delay(30_000)
                val online = peerMap.values.any { it.isOnline && !it.isUnreachable }
                if (!online) {
                    val targets = NetworkUtils.getClassCSubnetTargets(localIpAddress.value)
                    val subset = targets.filterIndexed { index, _ -> index % 3 == 0 }.take(85)
                    for (ip in subset) {
                        if (!isActive) break
                        p2pServer?.sendUdpUnicast(
                            ip,
                            P2PPacket(
                                type = SignalType.PING,
                                senderId = myDeviceId,
                                senderName = _myDisplayName.value,
                                targetId = GLOBAL_CHAT,
                                payload = System.currentTimeMillis().toString()
                            )
                        )
                        delay(35)
                    }
                }
            }
        }

        repositoryScope.launch {
            chatDao.markStalePeersOffline(System.currentTimeMillis() - OFFLINE_MS)
        }
    }

    fun stopP2PServices() {
        started = false
        discoveryJob?.cancel()
        maintenanceJob?.cancel()
        cleanupJob?.cancel()
        lanScanJob?.cancel()
        discoveryJob = null
        maintenanceJob = null
        cleanupJob = null
        lanScanJob = null
        nsdHelper?.shutdown()
        nsdHelper = null
        p2pServer?.stop()
        p2pServer = null
        udpAudioEngine.release()
        localCallEngine.endCall()
        _activeCall.value = null
        _incomingCall.value = null
        multicastLockManager.release()
    }

    fun updateDisplayName(newName: String) {
        val trimmed = newName.trim().take(32)
        if (trimmed.isBlank()) return
        prefs.edit().putString("custom_display_name", trimmed).apply()
        _myDisplayName.value = trimmed
        nsdHelper?.registerService(trimmed)
        sendBroadcastPing()
        _toasts.tryEmit("\u2713 Nickname updated")
    }

    fun setChannel(channel: Int) {
        _currentChannel.value = channel.coerceIn(1, 8)
        prefs.edit().putInt("current_channel", _currentChannel.value).apply()
    }

    fun startTransmitting() {
        val targets = peerMap.values
            .filter { it.isOnline && !it.isUnreachable }
            .map { it.ipAddress }
        udpAudioEngine.startSpeaking(targets)
    }

    fun stopTransmitting() {
        udpAudioEngine.stopSpeaking()
    }

    fun setListeningEnabled(enabled: Boolean) {
        udpAudioEngine.setListeningEnabled(enabled)
    }

    fun getMessagesForChat(chatId: String): Flow<List<MessageEntity>> =
        chatDao.getMessagesForChat(chatId)

    fun getGlobalMessages(): Flow<List<MessageEntity>> = chatDao.getGlobalMessages()

    fun peerForChat(chatId: String): PeerEntity? {
        if (chatId == GLOBAL_CHAT || chatId.startsWith(CHANNEL_PREFIX_VALUE)) return null
        return peerMap[chatId] ?: peerMap.values.firstOrNull { it.ipAddress == chatId }
    }

    fun sendTextMessage(targetChatId: String, content: String) {
        if (content.isBlank()) return
        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val message = MessageEntity(
            id = messageId,
            senderId = myDeviceId,
            senderName = _myDisplayName.value,
            receiverId = targetChatId,
            content = content,
            type = MessageType.TEXT,
            timestamp = now,
            isOutgoing = true,
            status = MessageStatus.SENDING
        )
        repositoryScope.launch {
            chatDao.insertMessage(message)
            updatePeerLastMessage(targetChatId, now, content.take(80), false)
            val packet = P2PPacket(
                type = SignalType.CHAT_MSG,
                senderId = myDeviceId,
                senderName = _myDisplayName.value,
                targetId = targetChatId,
                payload = content,
                msgId = messageId
            )
            val success = sendToChat(targetChatId, packet)
            chatDao.updateMessageStatus(
                messageId,
                if (success) MessageStatus.SENT else MessageStatus.FAILED
            )
            if (!success) _toasts.tryEmit("Message could not be delivered")
        }
    }

    fun sendMediaFile(targetChatId: String, file: File, type: MessageType) {
        if (!file.exists()) return
        val now = System.currentTimeMillis()
        val messageId = UUID.randomUUID().toString()
        val message = MessageEntity(
            id = messageId,
            senderId = myDeviceId,
            senderName = _myDisplayName.value,
            receiverId = targetChatId,
            content = file.name,
            type = type,
            mediaPath = file.absolutePath,
            fileSize = file.length(),
            timestamp = now,
            isOutgoing = true,
            status = MessageStatus.SENDING
        )
        repositoryScope.launch {
            chatDao.insertMessage(message)
            updatePeerLastMessage(targetChatId, now, snippetForType(type), false)
            val header = P2PPacket(
                type = SignalType.FILE_HEADER,
                senderId = myDeviceId,
                senderName = _myDisplayName.value,
                targetId = targetChatId,
                payload = file.name,
                extraData = file.length().toString(),
                msgId = messageId
            )
            val recipients = recipientsFor(targetChatId)
            var success = false
            for (ip in recipients) {
                success = (p2pServer?.sendFile(ip, header, file) ?: false) || success
            }
            chatDao.updateMessageStatus(
                messageId,
                if (success) MessageStatus.SENT else MessageStatus.FAILED
            )
            if (!success) _toasts.tryEmit("File could not be delivered")
        }
    }

    fun storeIncomingUri(uri: Uri, displayName: String? = null): File? {
        return try {
            val name = displayName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
            val file = File(context.cacheDir, "${System.currentTimeMillis()}-${name.replace('/', '_')}")
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy attachment", e)
            null
        }
    }

    fun initiateCall(targetPeerIp: String, isVideoCall: Boolean) {
        if (_activeCall.value != null || _incomingCall.value != null) return
        val offer = CallOffer(myDeviceId, _myDisplayName.value, targetPeerIp, isVideoCall)
        _activeCall.value = offer
        repositoryScope.launch {
            val invite = P2PPacket(
                type = SignalType.CALL_INVITE,
                senderId = myDeviceId,
                senderName = _myDisplayName.value,
                targetId = targetPeerIp,
                payload = localIpAddress.value,
                extraData = if (isVideoCall) "true" else "false"
            )
            p2pServer?.sendPacket(targetPeerIp, invite)
            localCallEngine.createSdpOffer(targetPeerIp)
            _toasts.tryEmit(if (isVideoCall) "Video call ringing..." else "Voice call ringing...")
        }
    }

    fun acceptIncomingCall() {
        val offer = _incomingCall.value ?: return
        _incomingCall.value = null
        _activeCall.value = offer
        repositoryScope.launch {
            val accept = P2PPacket(
                type = SignalType.CALL_ACCEPT,
                senderId = myDeviceId,
                senderName = _myDisplayName.value,
                targetId = offer.callerIp,
                payload = localIpAddress.value,
                extraData = if (offer.isVideoCall) "true" else "false"
            )
            p2pServer?.sendPacket(offer.callerIp, accept)
            localCallEngine.createSdpAnswer(offer.callerIp)
        }
    }

    fun declineIncomingCall() {
        val offer = _incomingCall.value ?: return
        _incomingCall.value = null
        repositoryScope.launch {
            val decline = P2PPacket(
                type = SignalType.CALL_DECLINE,
                senderId = myDeviceId,
                senderName = _myDisplayName.value,
                targetId = offer.callerIp
            )
            p2pServer?.sendPacket(offer.callerIp, decline)
        }
    }

    fun endCurrentCall() {
        val active = _activeCall.value
        val incoming = _incomingCall.value
        _activeCall.value = null
        _incomingCall.value = null
        localCallEngine.endCall()
        val target = active?.callerIp ?: incoming?.callerIp
        if (target != null) {
            repositoryScope.launch {
                p2pServer?.sendPacket(
                    target,
                    P2PPacket(
                        type = SignalType.CALL_END,
                        senderId = myDeviceId,
                        senderName = _myDisplayName.value,
                        targetId = target
                    )
                )
            }
        }
    }

    fun markChatAsRead(chatId: String) {
        if (chatId.isBlank() || chatId == GLOBAL_CHAT) return
        repositoryScope.launch {
            chatDao.markIncomingAsRead(chatId)
            val peer = peerMap[chatId] ?: peerMap.values.firstOrNull { it.ipAddress == chatId }
            if (peer != null) {
                chatDao.clearUnread(peer.id)
                val updated = peer.copy(unreadCount = 0)
                peerMap[peer.ipAddress] = updated
                _discoveredPeers.value = peerMap.toMap()
                chatDao.insertOrUpdatePeer(updated)
            }
            val peerIp = peer?.ipAddress ?: chatId
            p2pServer?.sendPacket(
                peerIp,
                P2PPacket(
                    type = SignalType.MSG_READ,
                    senderId = myDeviceId,
                    senderName = _myDisplayName.value,
                    targetId = chatId,
                    payload = chatId
                )
            )
        }
    }

    fun toggleMessageStar(msgId: String, isStarred: Boolean) {
        repositoryScope.launch { chatDao.setMessageStarred(msgId, isStarred) }
    }

    fun connectToIp(ip: String) {
        val target = ip.trim()
        if (!Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(target)) {
            _toasts.tryEmit("Enter a valid IPv4 address")
            return
        }
        repositoryScope.launch {
            val ping = P2PPacket(
                type = SignalType.PING,
                senderId = myDeviceId,
                senderName = _myDisplayName.value,
                targetId = GLOBAL_CHAT,
                payload = System.currentTimeMillis().toString()
            )
            val ok = p2pServer?.sendPacket(target, ping) ?: false
            if (ok) {
                touchPeer(target, "Peer-$target", target, System.currentTimeMillis(), true)
                _toasts.tryEmit("Contacted $target")
            } else {
                _toasts.tryEmit("Could not reach $target")
            }
        }
    }

    fun setAutoDeleteOldMessages(enabled: Boolean) {
        autoDeleteOldMessages.value = enabled
        prefs.edit().putBoolean("auto_delete_old_messages", enabled).apply()
        if (enabled) startAutoCleanupTask() else cleanupJob?.cancel()
    }

    fun cleanUpOldMessagesNow() {
        repositoryScope.launch {
            try {
                val count = chatDao.deleteOldUnstarred(
                    System.currentTimeMillis() - TWO_DAYS_MS
                )
                _toasts.tryEmit(
                    if (count > 0) "Auto-cleaned $count messages"
                    else "Cleaned up non-starred messages older than 2 days!"
                )
            } catch (e: Exception) {
                _toasts.tryEmit("Cleanup failed: ${e.message}")
            }
        }
    }

    private fun startAutoCleanupTask() {
        cleanupJob?.cancel()
        cleanupJob = repositoryScope.launch {
            while (isActive) {
                if (autoDeleteOldMessages.value) {
                    try {
                        chatDao.deleteOldUnstarred(System.currentTimeMillis() - TWO_DAYS_MS)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error running message auto-cleanup", e)
                    }
                }
                delay(6 * 60 * 60 * 1000L)
            }
        }
    }

    private fun sendBroadcastPing() {
        repositoryScope.launch {
            p2pServer?.sendUdp(
                P2PPacket(
                    type = SignalType.PING,
                    senderId = myDeviceId,
                    senderName = _myDisplayName.value,
                    targetId = GLOBAL_CHAT,
                    payload = System.currentTimeMillis().toString()
                )
            )
        }
    }

    private fun sendToChat(chatId: String, packet: P2PPacket): Boolean {
        return if (chatId == GLOBAL_CHAT || chatId.startsWith(CHANNEL_PREFIX_VALUE)) {
            val udp = p2pServer?.sendUdp(packet) ?: false
            var tcp = false
            for (ip in recipientsFor(chatId)) {
                tcp = (p2pServer?.sendPacket(ip, packet) ?: false) || tcp
            }
            udp || tcp
        } else {
            val ip = peerMap[chatId]?.ipAddress ?: chatId
            p2pServer?.sendPacket(ip, packet) ?: false
        }
    }

    private fun recipientsFor(chatId: String): List<String> {
        return if (chatId == GLOBAL_CHAT || chatId.startsWith(CHANNEL_PREFIX_VALUE)) {
            peerMap.values.filter { it.isOnline }.map { it.ipAddress }.distinct()
        } else {
            listOf(peerMap[chatId]?.ipAddress ?: chatId)
        }
    }

    private fun sendCallPacket(type: SignalType, targetIp: String, payload: String) {
        repositoryScope.launch {
            p2pServer?.sendPacket(
                targetIp,
                P2PPacket(
                    type = type,
                    senderId = myDeviceId,
                    senderName = _myDisplayName.value,
                    targetId = targetIp,
                    payload = payload
                )
            )
        }
    }

    private fun handleIncomingPacket(packet: P2PPacket, senderIp: String) {
        if (packet.senderId == myDeviceId) return
        if (packet.type == SignalType.PING) {
            touchPeer(senderIp, packet.senderName, packet.senderId, System.currentTimeMillis(), true)
            repositoryScope.launch {
                p2pServer?.sendPacket(
                    senderIp,
                    P2PPacket(
                        type = SignalType.PONG,
                        senderId = myDeviceId,
                        senderName = _myDisplayName.value,
                        targetId = senderIp,
                        payload = packet.payload
                    )
                )
            }
            return
        }
        if (packet.type == SignalType.PONG) {
            val sentAt = packet.payload.toLongOrNull() ?: System.currentTimeMillis()
            updatePeerHeartbeat(senderIp, packet.senderName, (System.currentTimeMillis() - sentAt).coerceAtLeast(0))
            return
        }
        if (packet.targetId != GLOBAL_CHAT &&
            !packet.targetId.startsWith(CHANNEL_PREFIX_VALUE) &&
            packet.targetId != localIpAddress.value &&
            packet.targetId != myDeviceId
        ) {
            // A packet addressed to somebody else. ACKs carry a peer device id in
            // some builds, so only apply this filter to chat/media packets.
            if (packet.type == SignalType.CHAT_MSG || packet.type == SignalType.FILE_HEADER) return
        }

        repositoryScope.launch {
            when (packet.type) {
                SignalType.CHAT_MSG -> handleChatMessage(packet, senderIp)
                SignalType.MSG_ACK -> {
                    if (packet.payload.isNotBlank()) {
                        chatDao.updateMessageStatus(packet.payload, MessageStatus.DELIVERED)
                    }
                }
                SignalType.MSG_READ -> {
                    chatDao.markOutgoingAsRead(senderIp)
                    updatePeerHeartbeat(senderIp, packet.senderName, -1)
                }
                SignalType.PING, SignalType.PONG -> Unit
                SignalType.SDP_OFFER -> {
                    localCallEngine.handleRemoteSdpOffer(packet.payload)
                    updatePeerHeartbeat(senderIp, packet.senderName, -1)
                }
                SignalType.SDP_ANSWER -> {
                    localCallEngine.handleRemoteSdpAnswer(packet.payload)
                    updatePeerHeartbeat(senderIp, packet.senderName, -1)
                }
                SignalType.ICE_CANDIDATE -> {
                    localCallEngine.handleRemoteIceCandidate(
                        IceCandidate("0", 0, packet.payload)
                    )
                }
                SignalType.CALL_INVITE -> handleCallInvite(packet, senderIp)
                SignalType.CALL_ACCEPT -> {
                    updatePeerHeartbeat(senderIp, packet.senderName, -1)
                    _toasts.tryEmit("Call accepted")
                }
                SignalType.CALL_DECLINE -> {
                    if (_incomingCall.value?.callerIp == senderIp) _incomingCall.value = null
                    if (_activeCall.value?.callerIp == senderIp) {
                        _activeCall.value = null
                        localCallEngine.endCall()
                    }
                    _toasts.tryEmit("Call declined")
                }
                SignalType.CALL_END -> {
                    val active = _activeCall.value
                    val incoming = _incomingCall.value
                    if (active?.callerIp == senderIp || incoming?.callerIp == senderIp) {
                        _activeCall.value = null
                        _incomingCall.value = null
                        localCallEngine.endCall()
                        _toasts.tryEmit("Call ended")
                    }
                }
                SignalType.FILE_HEADER, SignalType.FILE_CHUNK -> Unit
            }
        }
    }

    private suspend fun handleChatMessage(packet: P2PPacket, senderIp: String) {
        val chatId = when {
            packet.targetId == GLOBAL_CHAT -> GLOBAL_CHAT
            packet.targetId.startsWith(CHANNEL_PREFIX_VALUE) -> packet.targetId
            else -> senderIp
        }
        val now = System.currentTimeMillis()
        val message = MessageEntity(
            id = packet.msgId,
            senderId = senderIp,
            senderName = packet.senderName.ifBlank { "Peer-$senderIp" },
            receiverId = chatId,
            content = packet.payload,
            type = MessageType.TEXT,
            timestamp = now,
            isOutgoing = false,
            status = MessageStatus.RECEIVED
        )
        chatDao.insertMessage(message)
        touchPeer(senderIp, packet.senderName, packet.senderId, now, true)
        if (chatId != GLOBAL_CHAT && !chatId.startsWith(CHANNEL_PREFIX_VALUE)) {
            updatePeerLastMessage(senderIp, now, packet.payload.take(80), true)
        }
        p2pServer?.sendPacket(
            senderIp,
            P2PPacket(
                type = SignalType.MSG_ACK,
                senderId = myDeviceId,
                senderName = _myDisplayName.value,
                targetId = senderIp,
                payload = packet.msgId
            )
        )
    }

    private fun handleIncomingFile(info: IncomingFile) {
        val chatId = when {
            info.targetId == GLOBAL_CHAT -> GLOBAL_CHAT
            info.targetId.startsWith(CHANNEL_PREFIX_VALUE) -> info.targetId
            else -> info.senderIp
        }
        val type = inferMessageType(info.fileName)
        val now = System.currentTimeMillis()
        val message = MessageEntity(
            id = info.msgId,
            senderId = info.senderIp,
            senderName = info.senderName.ifBlank { "Peer-${info.senderIp}" },
            receiverId = chatId,
            content = info.fileName,
            type = type,
            mediaPath = info.file.absolutePath,
            fileSize = info.fileSize,
            timestamp = now,
            isOutgoing = false,
            status = MessageStatus.RECEIVED
        )
        repositoryScope.launch {
            chatDao.insertMessage(message)
            touchPeer(info.senderIp, info.senderName, info.senderId, now, true)
            if (chatId != GLOBAL_CHAT && !chatId.startsWith(CHANNEL_PREFIX_VALUE)) {
                updatePeerLastMessage(info.senderIp, now, "Sent a file: ${info.fileName}", true)
            }
            _toasts.tryEmit("Received ${info.fileName}")
        }
    }

    private fun handleCallInvite(packet: P2PPacket, senderIp: String) {
        val callerIp = packet.payload.takeIf {
            Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(it)
        } ?: senderIp
        if (_activeCall.value != null || _incomingCall.value != null) {
            repositoryScope.launch {
                p2pServer?.sendPacket(
                    senderIp,
                    P2PPacket(
                        type = SignalType.CALL_DECLINE,
                        senderId = myDeviceId,
                        senderName = _myDisplayName.value,
                        targetId = senderIp
                    )
                )
            }
            return
        }
        _incomingCall.value = CallOffer(
            callerId = packet.senderId,
            callerName = packet.senderName.ifBlank { "Peer-$callerIp" },
            callerIp = callerIp,
            isVideoCall = packet.extraData == "true"
        )
        touchPeer(callerIp, packet.senderName, packet.senderId, System.currentTimeMillis(), true)
    }

    private fun onNsdPeer(name: String, host: String, port: Int) {
        if (host == localIpAddress.value) return
        touchPeer(host, name.removePrefix("WalkieTalkie-"), host, System.currentTimeMillis(), true)
        repositoryScope.launch {
            p2pServer?.sendPacket(
                host,
                P2PPacket(
                    type = SignalType.PING,
                    senderId = myDeviceId,
                    senderName = _myDisplayName.value,
                    targetId = GLOBAL_CHAT,
                    payload = System.currentTimeMillis().toString()
                )
            )
        }
    }

    private fun touchPeer(
        ip: String,
        displayName: String,
        deviceId: String,
        now: Long,
        online: Boolean
    ) {
        if (ip.isBlank() || ip == localIpAddress.value) return
        val existing = peerMap[ip]
        val name = displayName.ifBlank {
            existing?.displayName ?: "Peer-$ip"
        }
        val peer = (existing ?: PeerEntity(
            id = deviceId.ifBlank { ip },
            displayName = name,
            ipAddress = ip,
            port = SERVER_PORT,
            avatarColor = avatarFor(ip)
        )).copy(
            id = deviceId.ifBlank { existing?.id ?: ip },
            displayName = name,
            ipAddress = ip,
            lastSeen = now,
            isOnline = online,
            isUnreachable = false,
            channel = _currentChannel.value
        )
        peerMap[ip] = peer
        _discoveredPeers.value = peerMap.toMap()
        repositoryScope.launch { chatDao.insertOrUpdatePeer(peer) }
    }

    private fun updatePeerHeartbeat(senderIp: String, senderName: String, rtt: Long) {
        val existing = peerMap[senderIp]
        val peer = (existing ?: PeerEntity(
            id = senderIp,
            displayName = senderName.ifBlank { "Peer-$senderIp" },
            ipAddress = senderIp,
            avatarColor = avatarFor(senderIp)
        )).copy(
            displayName = senderName.ifBlank { existing?.displayName ?: "Peer-$senderIp" },
            lastSeen = System.currentTimeMillis(),
            isOnline = true,
            isUnreachable = false,
            rttMs = if (rtt >= 0) rtt else existing?.rttMs ?: -1L
        )
        peerMap[senderIp] = peer
        _discoveredPeers.value = peerMap.toMap()
        repositoryScope.launch { chatDao.insertOrUpdatePeer(peer) }
    }

    private fun updatePeerLastMessage(
        peerIp: String,
        time: Long,
        snippet: String,
        incrementUnread: Boolean
    ) {
        if (peerIp == GLOBAL_CHAT || peerIp.startsWith(CHANNEL_PREFIX_VALUE)) return
        val existing = peerMap[peerIp]
        val displayName = existing?.displayName ?: "Peer-$peerIp"
        val unread = (existing?.unreadCount ?: 0) + if (incrementUnread) 1 else 0
        val updated = PeerEntity(
            id = existing?.id ?: peerIp,
            displayName = displayName,
            ipAddress = peerIp,
            port = SERVER_PORT,
            lastSeen = System.currentTimeMillis(),
            isOnline = existing?.isOnline ?: true,
            channel = existing?.channel ?: _currentChannel.value,
            avatarColor = existing?.avatarColor ?: avatarFor(peerIp),
            rttMs = existing?.rttMs ?: -1L,
            isUnreachable = existing?.isUnreachable ?: false,
            lastMessageTime = time,
            lastMessageSnippet = snippet,
            unreadCount = unread
        )
        peerMap[peerIp] = updated
        _discoveredPeers.value = peerMap.toMap()
        repositoryScope.launch { chatDao.insertOrUpdatePeer(updated) }
    }

    private fun expireStalePeers() {
        val now = System.currentTimeMillis()
        var changed = false
        for ((ip, peer) in peerMap) {
            val age = now - peer.lastSeen
            val unreachable = age > UNREACHABLE_MS
            val offline = age > OFFLINE_MS
            if (peer.isUnreachable != unreachable || peer.isOnline != !offline) {
                val updated = peer.copy(isUnreachable = unreachable, isOnline = !offline)
                peerMap[ip] = updated
                repositoryScope.launch { chatDao.insertOrUpdatePeer(updated) }
                changed = true
            }
        }
        if (changed) _discoveredPeers.value = peerMap.toMap()
    }

    private fun avatarFor(ip: String): Int {
        val palette = intArrayOf(
            -12877066, -14653588, -10601537, -5516821,
            -10711452, -3198763, -12149859, -6842472
        )
        return palette[(ip.hashCode() and Int.MAX_VALUE) % palette.size]
    }
}

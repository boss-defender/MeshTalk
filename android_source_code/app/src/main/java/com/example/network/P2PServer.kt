package com.example.network

import android.content.Context
import android.util.Log
import com.example.util.FileStorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.concurrent.thread

data class IncomingFile(
    val file: File,
    val fileName: String,
    val fileSize: Long,
    val senderId: String,
    val senderName: String,
    val senderIp: String,
    val targetId: String,
    val msgId: String
)

data class FileProgress(
    val id: String,
    val name: String,
    val sentBytes: Long,
    val totalBytes: Long,
    val direction: String
)

/**
 * TCP messenger/file server (8888) and UDP discovery transport (8887).
 * Frame format is byte-compatible with the original APK.
 */
class P2PServer(
    private val context: Context,
    private val selfId: () -> String,
    private val onPacket: (P2PPacket, String) -> Unit,
    private val onFileReceived: (IncomingFile) -> Unit,
    private val onProgress: ((FileProgress) -> Unit)? = null,
    private val onError: ((String) -> Unit)? = null
) {
    private val tag = "LocalP2PServer"
    private val executor = Executors.newCachedThreadPool()
    private val processedMessages: MutableSet<String> =
        Collections.synchronizedSet(LinkedHashSet<String>())
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var multicastSocket: MulticastSocket? = null
    @Volatile private var running = false
    private var tcpThread: Thread? = null
    private var udpThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        try {
            serverSocket = ServerSocket(TCP_PORT).apply { reuseAddress = true }
            Log.i(tag, "TCP P2P Server listening on port $TCP_PORT")
        } catch (e: Exception) {
            running = false
            onError?.invoke("TCP Server socket error: ${e.message}")
            return
        }
        try {
            val multicast = MulticastSocket(null)
            multicast.reuseAddress = true
            multicast.bind(InetSocketAddress(UDP_DISCOVERY_PORT))
            multicast.timeToLive = 4
            try {
                multicast.joinGroup(InetAddress.getByName(MULTICAST_IP))
            } catch (e: Exception) {
                Log.w(tag, "Multicast group join failed; UDP broadcast will still be used", e)
            }
            multicastSocket = multicast
            udpSocket = multicast
        } catch (e: Exception) {
            Log.w(tag, "Multicast socket bind error", e)
            try {
                udpSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(UDP_DISCOVERY_PORT))
                }
            } catch (fallback: Exception) {
                onError?.invoke("UDP Broadcast listener error: ${fallback.message}")
            }
        }
        udpSocket?.broadcast = true

        tcpThread = thread(name = "P2P-TCP-Server", isDaemon = true) {
            while (running) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    executor.execute { handleSocket(socket) }
                } catch (e: Exception) {
                    if (running) Log.w(tag, "TCP accept error", e)
                }
            }
        }
        udpThread = thread(name = "P2P-UDP-Discovery", isDaemon = true) {
            val buffer = ByteArray(65_507)
            while (running) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet) ?: break
                    val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    val parsed = P2PPacket.fromJson(text) ?: continue
                    val senderIp = packet.address.hostAddress?.removePrefix("::ffff:") ?: continue
                    if (parsed.senderId == selfId()) continue
                    if (!markProcessed(parsed.msgId)) continue
                    onPacket(parsed, senderIp)
                } catch (e: Exception) {
                    if (running) Log.w(tag, "UDP Broadcast listener error", e)
                }
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        try { multicastSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        udpSocket = null
        multicastSocket = null
        tcpThread?.interrupt()
        udpThread?.interrupt()
        executor.shutdownNow()
    }

    fun sendUdp(packet: P2PPacket): Boolean {
        val socket = udpSocket
        if (socket == null || socket.isClosed) return false
        val data = packet.toJson().toByteArray(Charsets.UTF_8)
        var sent = false
        for (target in NetworkUtils.getBroadcastTargets()) {
            try {
                socket.send(DatagramPacket(data, data.size, target, UDP_DISCOVERY_PORT))
                sent = true
            } catch (_: Exception) {
                // Try the remaining broadcast addresses.
            }
        }
        return sent
    }

    fun sendUdpUnicast(targetIp: String, packet: P2PPacket): Boolean {
        val socket = udpSocket
        if (socket == null || socket.isClosed || targetIp.isBlank()) return false
        return try {
            val data = packet.toJson().toByteArray(Charsets.UTF_8)
            val address = InetAddress.getByName(targetIp)
            socket.send(DatagramPacket(data, data.size, address, UDP_DISCOVERY_PORT))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun sendPacket(targetIp: String, packet: P2PPacket, port: Int = TCP_PORT): Boolean {
        if (targetIp.isBlank()) return false
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(targetIp, port), 4_000)
                socket.soTimeout = 4_000
                val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                writeFrame(out, PACKET_MAGIC, packet)
                out.flush()
            }
            true
        } catch (e: Exception) {
            Log.w(tag, "Failed to send packet to $targetIp", e)
            false
        }
    }

    fun sendFile(targetIp: String, header: P2PPacket, file: File, port: Int = TCP_PORT): Boolean {
        if (targetIp.isBlank() || !file.exists()) return false
        val total = file.length()
        val transferId = header.msgId
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(targetIp, port), 4_000)
                socket.soTimeout = 30 * 60 * 1_000
                val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                writeFrame(out, FILE_MAGIC, header)
                val iv = FileCrypto.newIv()
                out.write(iv)
                val cipher = FileCrypto.encrypt(iv)
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        val encrypted = cipher.update(buffer, 0, count)
                        if (encrypted != null && encrypted.isNotEmpty()) out.write(encrypted)
                        sent += count
                        onProgress?.invoke(
                            FileProgress(transferId, file.name, sent, total, "send")
                        )
                    }
                    val tail = cipher.doFinal()
                    if (tail.isNotEmpty()) out.write(tail)
                }
                out.flush()
            }
            Log.i(tag, "Successfully sent encrypted binary stream (${file.name})")
            true
        } catch (e: Exception) {
            Log.w(tag, "Failed to send encrypted binary stream file to $targetIp", e)
            onError?.invoke("File transfer failed: ${e.message}")
            false
        }
    }

    private fun handleSocket(socket: Socket) {
        val senderIp = socket.inetAddress?.hostAddress?.removePrefix("::ffff:") ?: return
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 20_000
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val marker = input.readUnsignedByte()
            val length = input.readInt()
            if (length <= 0 || length > 500_000) throw IllegalArgumentException("Invalid frame length $length")
            val json = ByteArray(length)
            input.readFully(json)
            val header = P2PPacket.fromJson(String(json, Charsets.UTF_8))
                ?: throw IllegalArgumentException("Invalid JSON header")
            if (header.senderId == selfId()) return
            when (marker) {
                PACKET_MAGIC -> {
                    if (!markProcessed(header.msgId)) return
                    onPacket(header, senderIp)
                }
                FILE_MAGIC -> receiveFile(socket, input, header, senderIp)
                else -> Unit
            }
        } catch (e: SocketTimeoutException) {
            Log.w(tag, "Connection timeout from $senderIp")
        } catch (e: Exception) {
            if (running) Log.w(tag, "Error handling client $senderIp", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun receiveFile(
        socket: Socket,
        input: DataInputStream,
        header: P2PPacket,
        senderIp: String
    ) {
        val expected = header.extraData?.toLongOrNull() ?: 0L
        if (expected <= 0) return
        socket.soTimeout = 30 * 60 * 1_000
        val iv = ByteArray(16)
        input.readFully(iv)
        val cipher = FileCrypto.decrypt(iv)
        val dir = FileStorageManager.getStorageDir(context)
        val finalFile = FileStorageManager.uniqueFile(dir, header.payload.ifBlank { "file" })
        val tempFile = File(finalFile.parentFile, finalFile.name + ".part")
        var received = 0L
        try {
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (received < expected) {
                    val want = minOf(buffer.size.toLong(), expected - received).toInt()
                    val count = input.read(buffer, 0, want)
                    if (count <= 0) throw IllegalStateException("Socket closed during file transfer")
                    val plain = cipher.update(buffer, 0, count)
                    if (plain != null && plain.isNotEmpty()) output.write(plain)
                    received += count
                    onProgress?.invoke(
                        FileProgress(header.msgId, header.payload, received, expected, "receive")
                    )
                }
                val tail = cipher.doFinal()
                if (tail.isNotEmpty()) output.write(tail)
                output.flush()
            }
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }
            Log.i(tag, "Successfully received and decrypted binary stream (${finalFile.name})")
            onFileReceived(
                IncomingFile(
                    file = finalFile,
                    fileName = finalFile.name,
                    fileSize = finalFile.length(),
                    senderId = header.senderId,
                    senderName = header.senderName,
                    senderIp = senderIp,
                    targetId = header.targetId,
                    msgId = header.msgId
                )
            )
        } catch (e: Exception) {
            tempFile.delete()
            Log.w(tag, "Failed to receive encrypted binary stream", e)
            onError?.invoke("Failed to receive encrypted binary stream: ${e.message}")
        }
    }

    private fun writeFrame(out: OutputStream, marker: Int, packet: P2PPacket) {
        val json = packet.toJson().toByteArray(Charsets.UTF_8)
        val header = ByteArray(5)
        header[0] = marker.toByte()
        header[1] = (json.size ushr 24).toByte()
        header[2] = (json.size ushr 16).toByte()
        header[3] = (json.size ushr 8).toByte()
        header[4] = json.size.toByte()
        out.write(header)
        out.write(json)
    }

    private fun markProcessed(msgId: String): Boolean {
        if (msgId.isBlank()) return true
        synchronized(processedMessages) {
            if (!processedMessages.add(msgId)) return false
            if (processedMessages.size > 1_000) {
                val iterator = processedMessages.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
        return true
    }
}

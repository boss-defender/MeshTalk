package com.example.network

import com.example.data.MessageType
import org.json.JSONObject
import java.util.UUID

const val TCP_PORT = 8888
const val UDP_DISCOVERY_PORT = 8887
const val PTT_PORT = 9999
const val CALL_VIDEO_PORT = 8889
const val CALL_AUDIO_PORT = 8890
const val MULTICAST_IP = "239.255.0.1"
const val DISCOVERY_INTERVAL_MS = 5_000L
const val SAMPLE_RATE = 16_000
const val PACKET_MAGIC = 1
const val FILE_MAGIC = 2
const val GLOBAL_CHAT = "GLOBAL"
const val CHANNEL_PREFIX = "CHANNEL_"

enum class SignalType {
    CHAT_MSG,
    MSG_ACK,
    MSG_READ,
    FILE_HEADER,
    FILE_CHUNK,
    CALL_INVITE,
    CALL_ACCEPT,
    CALL_DECLINE,
    CALL_END,
    SDP_OFFER,
    SDP_ANSWER,
    ICE_CANDIDATE,
    PING,
    PONG
}

/** Wire model used by the APK. Field order and JSON keys are protocol-compatible. */
data class P2PPacket(
    val type: SignalType,
    val senderId: String,
    val senderName: String,
    val targetId: String = GLOBAL_CHAT,
    val payload: String = "",
    val extraData: String? = null,
    val msgId: String = UUID.randomUUID().toString()
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("type", type.name)
        json.put("senderId", senderId)
        json.put("senderName", senderName)
        json.put("targetId", targetId)
        json.put("payload", payload)
        json.put("msgId", msgId)
        if (extraData != null) json.put("extraData", extraData)
        return json.toString()
    }

    companion object {
        fun fromJson(text: String): P2PPacket? = try {
            val json = JSONObject(text)
            P2PPacket(
                type = SignalType.valueOf(json.getString("type")),
                senderId = json.getString("senderId"),
                senderName = json.getString("senderName"),
                targetId = json.optString("targetId", GLOBAL_CHAT),
                payload = json.optString("payload", ""),
                extraData = if (json.has("extraData")) json.getString("extraData") else null,
                msgId = json.optString("msgId", UUID.randomUUID().toString())
            )
        } catch (_: Exception) {
            null
        }
    }
}

fun inferMessageType(fileName: String): MessageType {
    return when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "webp", "gif" -> MessageType.IMAGE
        "mp4", "mkv", "3gp", "webm" -> MessageType.VIDEO
        "3gp", "aac", "m4a", "wav", "mp3", "ogg" -> MessageType.VOICE_NOTE
        else -> MessageType.FILE
    }
}

fun snippetForType(type: MessageType): String = when (type) {
    MessageType.IMAGE -> "You sent a photo"
    MessageType.VIDEO -> "You sent a video"
    MessageType.VOICE_NOTE -> "You sent a voice note"
    else -> "You sent a file"
}

fun sanitizeFileName(name: String): String =
    name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(180).ifBlank { "file" }

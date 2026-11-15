package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class MessageType {
    TEXT, IMAGE, VOICE_NOTE, VIDEO, FILE, SYSTEM
}

enum class MessageStatus {
    SENDING, SENT, DELIVERED, READ, FAILED, RECEIVED
}

/**
 * A chat message. [receiverId] is the chat key: a peer IP address, "GLOBAL",
 * or a channel key such as "CHANNEL_1".
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val senderName: String,
    val receiverId: String,
    val content: String,
    val type: MessageType = MessageType.TEXT,
    val mediaPath: String? = null,
    val fileSize: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val isOutgoing: Boolean = false,
    val status: MessageStatus = MessageStatus.SENT,
    val isStarred: Boolean = false
)

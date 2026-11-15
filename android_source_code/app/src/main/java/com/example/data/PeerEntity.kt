package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A LAN peer as persisted in Room. The primary key is the remote device id;
 * chat messages use the peer IP address as their receiver id, matching the APK.
 */
@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val ipAddress: String,
    val port: Int = 8888,
    val lastSeen: Long = System.currentTimeMillis(),
    val isOnline: Boolean = true,
    val channel: Int = 1,
    val avatarColor: Int = -12877066,
    val rttMs: Long = -1L,
    val isUnreachable: Boolean = false,
    val lastMessageTime: Long = 0L,
    val lastMessageSnippet: String? = null,
    val unreadCount: Int = 0
)

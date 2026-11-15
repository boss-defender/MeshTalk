package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdatePeer(peer: PeerEntity)

    @Query("SELECT * FROM messages WHERE receiverId = :chatId ORDER BY timestamp ASC")
    fun getMessagesForChat(chatId: String): Flow<List<MessageEntity>>

    @Query(
        "SELECT * FROM messages WHERE receiverId = 'GLOBAL' " +
            "OR receiverId LIKE 'CHANNEL_%' ORDER BY timestamp ASC"
    )
    fun getGlobalMessages(): Flow<List<MessageEntity>>

    @Query(
        "SELECT * FROM peers ORDER BY CASE " +
            "WHEN lastMessageTime > lastSeen THEN lastMessageTime ELSE lastSeen END DESC"
    )
    fun getAllPeers(): Flow<List<PeerEntity>>

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: MessageStatus)

    @Query("UPDATE messages SET status = 'READ' WHERE (receiverId = :peerIp OR senderId = :peerIp) AND isOutgoing = 1")
    suspend fun markOutgoingAsRead(peerIp: String)

    @Query("UPDATE messages SET status = 'READ' WHERE (senderId = :peerIp OR receiverId = :peerIp) AND isOutgoing = 0")
    suspend fun markIncomingAsRead(peerIp: String)

    @Query("UPDATE peers SET unreadCount = 0 WHERE id = :peerId")
    suspend fun clearUnread(peerId: String)

    @Query("UPDATE peers SET isOnline = 0 WHERE lastSeen < :cutoff")
    suspend fun markStalePeersOffline(cutoff: Long)

    @Query("UPDATE messages SET isStarred = :isStarred WHERE id = :messageId")
    suspend fun setMessageStarred(messageId: String, isStarred: Boolean)

    @Query("DELETE FROM messages WHERE isStarred = 0 AND timestamp < :cutoff")
    suspend fun deleteOldUnstarred(cutoff: Long): Int

    @Query("DELETE FROM messages WHERE receiverId = :chatId OR senderId = :chatId")
    suspend fun deleteChat(chatId: String)
}

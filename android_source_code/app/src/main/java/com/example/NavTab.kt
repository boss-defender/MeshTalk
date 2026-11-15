package com.example

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.ui.graphics.vector.ImageVector

enum class NavTab(val title: String, val icon: ImageVector, val tag: String) {
    CHATS_AND_CALLS("Chats & Calls", Icons.AutoMirrored.Filled.Chat, "chats_and_calls"),
    BROADCAST_HUB("Broadcast Hub", Icons.Filled.RssFeed, "broadcast_hub"),
    WALKIE_TALKIE("Walkie-Talkie", Icons.Filled.Radio, "walkie_talkie")
}

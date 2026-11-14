package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.NavTab
import com.example.repository.P2PConnectionStatus
import com.example.ui.theme.MeshAmber
import com.example.ui.theme.MeshBackground
import com.example.ui.theme.MeshGreen
import com.example.ui.theme.MeshRed
import com.example.ui.theme.MeshSecondary
import com.example.ui.theme.MeshSurface
import com.example.ui.theme.MeshText

@Composable
fun MeshTopBar(
    status: P2PConnectionStatus,
    peerCount: Int,
    onEditName: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MeshAmber.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.WifiTethering,
                    contentDescription = null,
                    tint = MeshAmber,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "MeshTalk",
                    color = MeshText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.size(3.dp))
                P2PStatusBadge(status, peerCount)
            }
            IconButton(onClick = onEditName) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit name", tint = MeshSecondary)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = MeshSecondary)
            }
        }
    }
}

@Composable
fun P2PStatusBadge(
    status: P2PConnectionStatus,
    peerCount: Int,
    modifier: Modifier = Modifier
) {
    val color = when (status) {
        P2PConnectionStatus.CONNECTED -> MeshGreen
        P2PConnectionStatus.SEARCHING -> MeshAmber
        P2PConnectionStatus.DISCONNECTED -> MeshRed
    }
    val text = when (status) {
        P2PConnectionStatus.CONNECTED -> "Connected ($peerCount ${if (peerCount == 1) "peer" else "peers"})"
        P2PConnectionStatus.SEARCHING -> "Searching..."
        P2PConnectionStatus.DISCONNECTED -> "Disconnected"
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp
        )
    }
}

@Composable
fun MeshBottomBar(current: NavTab, onSelect: (NavTab) -> Unit) {
    NavigationBar(
        containerColor = MeshSurface,
        tonalElevation = 0.dp
    ) {
        NavTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = current == tab,
                onClick = { onSelect(tab) },
                icon = {
                    Icon(
                        tab.icon,
                        contentDescription = tab.title,
                        modifier = Modifier.size(22.dp)
                    )
                },
                label = {
                    Text(
                        text = tab.title,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MeshBackground,
                    selectedTextColor = MeshAmber,
                    indicatorColor = MeshAmber,
                    unselectedIconColor = MeshSecondary,
                    unselectedTextColor = MeshSecondary
                )
            )
        }
    }
}

@Composable
fun ScreenHeader(
    eyebrow: String,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            SectionLabel(eyebrow)
            Spacer(Modifier.size(5.dp))
            Text(title, color = MeshText, style = MaterialTheme.typography.headlineMedium)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.size(4.dp))
                Text(subtitle, color = MeshSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        trailing?.invoke()
    }
}

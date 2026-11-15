package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.repository.P2PConnectionStatus
import com.example.ui.components.DividerLine
import com.example.ui.components.InfoPill
import com.example.ui.components.LabelValue
import com.example.ui.components.MeshCard
import com.example.ui.components.SectionLabel
import com.example.ui.theme.MeshAmber
import com.example.ui.theme.MeshBlue
import com.example.ui.theme.MeshCard as MeshCardColor
import com.example.ui.theme.MeshGreen
import com.example.ui.theme.MeshRed
import com.example.ui.theme.MeshSecondary
import com.example.ui.theme.MeshText

@Composable
fun SettingsScreen(
    currentDisplayName: String,
    deviceId: String,
    localIp: String,
    wifiSsid: String,
    status: P2PConnectionStatus,
    peerCount: Int,
    currentChannel: Int,
    autoDeleteOldMessages: Boolean,
    isOledTheme: Boolean,
    onSaveDisplayName: (String) -> Unit,
    onChannelChange: (Int) -> Unit,
    onAutoDeleteChange: (Boolean) -> Unit,
    onCleanUp: () -> Unit,
    onToggleTheme: () -> Unit,
    onBack: () -> Unit
) {
    var nameInput by remember(currentDisplayName) { mutableStateOf(currentDisplayName) }
    var showCleanUpDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MeshText
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                SectionLabel("Settings")
                Text(
                    "Settings & Diagnostics",
                    color = MeshText,
                    style = MaterialTheme.typography.headlineMedium
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            SettingsSection(icon = Icons.Filled.Person, title = "Your Device Nickname") {
                Text(
                    "This name will be broadcast to all other devices on the Wi-Fi network:",
                    color = MeshSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MeshAmber,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                        focusedTextColor = MeshText,
                        unfocusedTextColor = MeshText,
                        cursorColor = MeshAmber
                    )
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onSaveDisplayName(nameInput) },
                    enabled = nameInput.isNotBlank() && nameInput != currentDisplayName,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MeshAmber,
                        disabledContainerColor = MeshCardColor
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save Nickname", color = Color(0xFF1A1C1E), fontWeight = FontWeight.Bold)
                }
            }

            SettingsSection(icon = Icons.Filled.Info, title = "Device Profile") {
                LabelValue("Name", currentDisplayName)
                DividerLine()
                LabelValue("Device ID", deviceId)
                DividerLine()
                LabelValue("Local IP", localIp)
                DividerLine()
                LabelValue("Connected Wi-Fi", wifiSsid)
            }

            SettingsSection(icon = Icons.Filled.Wifi, title = "Local Network Diagnostics") {
                val statusText = when (status) {
                    P2PConnectionStatus.CONNECTED -> "Connected (Active Mesh Peers Found)"
                    P2PConnectionStatus.SEARCHING -> "Searching (Scanning for Nearby Devices...)"
                    P2PConnectionStatus.DISCONNECTED -> "Disconnected (Wi-Fi/Network Offline)"
                }
                val statusColor = when (status) {
                    P2PConnectionStatus.CONNECTED -> MeshGreen
                    P2PConnectionStatus.SEARCHING -> MeshAmber
                    P2PConnectionStatus.DISCONNECTED -> MeshRed
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("P2P Mesh Status", color = MeshText, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            statusText,
                            color = statusColor,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    InfoPill(if (peerCount == 1) "1 peer" else "$peerCount peers", statusColor)
                }
                DividerLine()
                LabelValue("Network Status", if (localIp == "127.0.0.1") "Disconnected" else "Connected ($localIp)")
                DividerLine()
                LabelValue("mDNS NSD Discovery", "Active (_walkietalkie._tcp.)", MeshGreen)
                DividerLine()
                LabelValue("MulticastLock State", "Acquired (High Performance)", MeshGreen)
                DividerLine()
                LabelValue("TCP Messenger Port", "8888")
                DividerLine()
                LabelValue("UDP Walkie-Talkie Port", "9999")
                DividerLine()
                LabelValue("Radio Channel", "CH-$currentChannel")
            }

            SettingsSection(icon = Icons.Filled.CleaningServices, title = "Storage & Message Auto-Cleanup") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Auto-Delete Non-Starred Messages",
                            color = MeshText,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Purges non-starred messages older than 2 days. Files are saved in Download/MeshTalkFiles.",
                            color = MeshSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = autoDeleteOldMessages,
                        onCheckedChange = onAutoDeleteChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF1A1C1E),
                            checkedTrackColor = MeshAmber,
                            uncheckedThumbColor = MeshSecondary,
                            uncheckedTrackColor = MeshCardColor
                        )
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showCleanUpDialog = true },
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MeshRed.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Filled.CleaningServices,
                        contentDescription = null,
                        tint = MeshRed,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Clean Up", color = MeshRed)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Default Storage Path: Download/MeshTalkFiles",
                    color = MeshSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            SettingsSection(icon = Icons.Filled.Palette, title = "Appearance") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Toggle Dark/OLED Theme", color = MeshText, style = MaterialTheme.typography.titleMedium)
                        Text(
                            isOledThemeText(isOledTheme),
                            color = MeshSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Switch(
                        checked = isOledTheme,
                        onCheckedChange = { onToggleTheme() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF1A1C1E),
                            checkedTrackColor = MeshAmber,
                            uncheckedThumbColor = MeshSecondary,
                            uncheckedTrackColor = MeshCardColor
                        )
                    )
                }
            }

            SettingsSection(icon = Icons.Filled.Info, title = "P2P Guide") {
                GuideStep("1", "Connect 2 Android phones to the same Wi-Fi router or hotspot.")
                GuideStep("2", "Open this Walkie-Talkie app on both devices.")
                GuideStep("3", "Check the 'Radar' screen. Auto-discovery will find nearby peers instantly.")
                GuideStep("4", "Select a Channel (e.g. CH-1) on the Walkie-Talkie tab and hold 'HOLD TO SPEAK'.")
                Spacer(Modifier.height(4.dp))
                Text(
                    "Starred messages preserved. Audio plays instantly on the peer phone without internet!",
                    color = MeshSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    if (showCleanUpDialog) {
        AlertDialog(
            onDismissRequest = { showCleanUpDialog = false },
            title = { Text("Clear Old Storage & Chat History?") },
            text = {
                Text(
                    "This will delete non-starred chat messages older than 2 days to free up space. " +
                        "Starred messages and downloaded files in Download/MeshTalkFiles will be preserved."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onCleanUp()
                        showCleanUpDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MeshRed)
                ) {
                    Text("Clear Storage")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanUpDialog = false }) { Text("Cancel") }
            }
        )
    }
}

private fun isOledThemeText(isOled: Boolean) = if (isOled) "OLED Black active" else "Soft dark theme active"

@Composable
private fun SettingsSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable () -> Unit
) {
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MeshAmber.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MeshAmber, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(9.dp))
        SectionLabel(title)
    }
    Spacer(Modifier.height(8.dp))
    MeshCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun GuideStep(step: String, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(MeshBlue.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                step,
                color = MeshBlue,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            color = MeshSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}

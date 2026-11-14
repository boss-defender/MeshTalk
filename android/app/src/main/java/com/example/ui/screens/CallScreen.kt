package com.example.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.network.LocalCallEngine
import com.example.repository.CallOffer
import com.example.ui.components.PeerAvatar
import com.example.ui.components.SectionLabel
import com.example.ui.theme.MeshAmber
import com.example.ui.theme.MeshBackground
import com.example.ui.theme.MeshBlue
import com.example.ui.theme.MeshGreen
import com.example.ui.theme.MeshRed
import com.example.ui.theme.MeshSecondary
import com.example.ui.theme.MeshText

@Composable
fun ActiveCallScreen(
    callOffer: CallOffer,
    callEngine: LocalCallEngine,
    remoteVideoBitmap: Bitmap?,
    isMicMuted: Boolean,
    isVideoEnabled: Boolean,
    isSpeakerOn: Boolean,
    onEndCall: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var started by remember(callOffer.callerIp) { mutableStateOf(false) }
    LaunchedEffect(callOffer.callerIp) {
        if (!started) {
            started = true
            callEngine.startCall(callOffer.callerIp, callOffer.isVideoCall, lifecycleOwner)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090A0C))
    ) {
        if (callOffer.isVideoCall && remoteVideoBitmap != null) {
            Image(
                bitmap = remoteVideoBitmap.asImageBitmap(),
                contentDescription = "Remote Video Stream",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PeerAvatar(
                    name = callOffer.callerName,
                    size = 108.dp,
                    online = true
                )
                Spacer(Modifier.height(22.dp))
                Text(
                    text = callOffer.callerName,
                    color = MeshText,
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "IP: ${callOffer.callerIp}",
                    color = MeshSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(18.dp))
                SectionLabel(if (callOffer.isVideoCall) "HD Video Call Active" else "Voice Session Active")
                if (callOffer.isVideoCall && remoteVideoBitmap == null) {
                    Spacer(Modifier.height(10.dp))
                    Text("Remote Video Stream", color = MeshSecondary, fontSize = 12.sp)
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (callOffer.isVideoCall) MeshBlue else MeshGreen)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = if (callOffer.isVideoCall) "HD VIDEO CALL ACTIVE" else "VOICE SESSION ACTIVE",
                color = MeshText,
                style = MaterialTheme.typography.labelSmall
            )
        }

        if (isVideoEnabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(18.dp)
                    .size(width = 108.dp, height = 156.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { context ->
                        PreviewView(context).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                    },
                    update = { preview ->
                        callEngine.setupLocalPreview(preview, callOffer.callerIp, lifecycleOwner)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 44.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CallControl(
                icon = if (isMicMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                label = if (isMicMuted) "MIC MUTED" else "MIC ON",
                tint = if (isMicMuted) MeshRed else MeshText,
                onClick = { callEngine.toggleMic() }
            )
            Spacer(Modifier.width(14.dp))
            CallControl(
                icon = if (isSpeakerOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                label = if (isSpeakerOn) "SPEAKER" else "EARPIECE",
                tint = if (isSpeakerOn) MeshAmber else MeshText,
                onClick = { callEngine.toggleSpeaker() }
            )
            Spacer(Modifier.width(14.dp))
            CallControl(
                icon = if (isVideoEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                label = "Toggle Video",
                tint = if (isVideoEnabled) MeshBlue else MeshText,
                onClick = { callEngine.toggleVideo(callOffer.callerIp, lifecycleOwner) }
            )
            if (isVideoEnabled) {
                Spacer(Modifier.width(14.dp))
                CallControl(
                    icon = Icons.Filled.Cameraswitch,
                    label = "Switch Camera",
                    tint = MeshText,
                    onClick = { callEngine.switchCamera(callOffer.callerIp, lifecycleOwner) }
                )
            }
            Spacer(Modifier.width(20.dp))
            Box(
                modifier = Modifier
                    .size(66.dp)
                    .clip(CircleShape)
                    .background(MeshRed)
                    .clickable(onClick = onEndCall),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.CallEnd,
                    contentDescription = "End Call",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
private fun CallControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.09f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(25.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = MeshSecondary, style = MaterialTheme.typography.labelSmall, fontSize = 8.sp)
    }
}

@Composable
fun IncomingCallOverlay(
    callOffer: CallOffer,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xEE090A0C)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PeerAvatar(name = callOffer.callerName, size = 104.dp, online = true)
            Spacer(Modifier.height(20.dp))
            SectionLabel("Incoming Local Call")
            Spacer(Modifier.height(6.dp))
            Text(
                callOffer.callerName,
                color = MeshText,
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = (if (callOffer.isVideoCall) "Video Call · IP: " else "Voice Call · IP: ") + callOffer.callerIp,
                color = MeshSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(30.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(MeshRed)
                            .clickable(onClick = onDecline),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.CallEnd, contentDescription = "Decline", tint = Color.White)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Decline", color = MeshSecondary, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.width(46.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(MeshGreen)
                            .clickable(onClick = onAccept),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Call, contentDescription = "Accept", tint = MeshBackground)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Accept", color = MeshSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

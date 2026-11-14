package com.example.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.InfoPill
import com.example.ui.components.MeshCard
import com.example.ui.components.SectionLabel
import com.example.ui.theme.MeshAmber
import com.example.ui.theme.MeshBackground
import com.example.ui.theme.MeshBlue
import com.example.ui.theme.MeshCard as MeshCardColor
import com.example.ui.theme.MeshGreen
import com.example.ui.theme.MeshRed
import com.example.ui.theme.MeshSecondary
import com.example.ui.theme.MeshText
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun WalkieTalkieScreen(
    channel: Int,
    isTransmitting: Boolean,
    isReceiving: Boolean,
    isListeningEnabled: Boolean,
    talkingPeer: String?,
    audioAmplitude: Float,
    audioWaveform: FloatArray,
    onChannelChange: (Int) -> Unit,
    onListeningChange: (Boolean) -> Unit,
    onStartTransmitting: () -> Unit,
    onStopTransmitting: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                SectionLabel("Frequency Channel")
                Spacer(Modifier.height(4.dp))
                Text(
                    "Walkie-Talkie",
                    color = MeshText,
                    style = MaterialTheme.typography.headlineMedium
                )
            }
            InfoPill("CH-$channel", MeshAmber)
        }

        Spacer(Modifier.height(14.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items((1..8).toList()) { ch ->
                val selected = ch == channel
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) MeshAmber else MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 9.dp)
                        .pointerInput(ch) {
                            detectTapGestures { onChannelChange(ch) }
                        }
                ) {
                    Text(
                        text = "CH-$ch",
                        color = if (selected) Color(0xFF1A1C1E) else MeshSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        MeshCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val stateText = when {
                    isTransmitting -> "SPEAKING"
                    isReceiving -> "RECEIVING"
                    else -> "IDLE"
                }
                val stateColor = when {
                    isTransmitting -> MeshGreen
                    isReceiving -> MeshBlue
                    else -> MeshSecondary
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(stateColor)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        stateText,
                        color = stateColor,
                        style = MaterialTheme.typography.labelMedium,
                        letterSpacing = 1.4.sp
                    )
                }
                Spacer(Modifier.height(14.dp))
                AudioWaveform(
                    active = isTransmitting || isReceiving,
                    transmitting = isTransmitting,
                    amplitude = audioAmplitude,
                    waveform = audioWaveform
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        isReceiving && !talkingPeer.isNullOrBlank() -> "Incoming audio from $talkingPeer"
                        isTransmitting -> "You are speaking to Channel $channel"
                        isListeningEnabled -> "Channel $channel is clear and listening"
                        else -> "Audio muted. Toggle switch above to listen."
                    },
                    color = MeshSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Box(contentAlignment = Alignment.Center) {
            val pulse by rememberInfiniteTransition(label = "pttPulse").animateFloat(
                initialValue = 0.92f,
                targetValue = 1.06f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pttPulseScale"
            )
            Box(
                modifier = Modifier
                    .size(if (isTransmitting) 206.dp else 192.dp)
                    .clip(CircleShape)
                    .background(
                        if (isTransmitting) MeshGreen.copy(alpha = 0.13f)
                        else MeshAmber.copy(alpha = 0.07f)
                    )
            )
            Box(
                modifier = Modifier
                    .size(164.dp * (if (isTransmitting) pulse else 1f))
                    .clip(CircleShape)
                    .background(if (isTransmitting) MeshGreen else MeshAmber)
                    .pointerInput(isTransmitting) {
                        detectTapGestures(
                            onPress = {
                                onStartTransmitting()
                                tryAwaitRelease()
                                onStopTransmitting()
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = "Push To Talk Mic",
                        tint = MeshBackground,
                        modifier = Modifier.size(38.dp)
                    )
                    Spacer(Modifier.height(7.dp))
                    Text(
                        text = if (isTransmitting) "SPEAKING" else "HOLD TO SPEAK",
                        color = MeshBackground,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "Press and hold to talk over local Wi-Fi",
            color = MeshSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(16.dp))
        MeshCard(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(MeshAmber.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = MeshAmber,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Walkie Talkie Background Listener",
                        color = MeshText,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Audio plays instantly on the peer phone without internet!",
                        color = MeshSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = isListeningEnabled,
                    onCheckedChange = onListeningChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MeshBackground,
                        checkedTrackColor = MeshAmber,
                        uncheckedThumbColor = MeshSecondary,
                        uncheckedTrackColor = MeshCardColor
                    )
                )
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun AudioWaveform(
    active: Boolean,
    transmitting: Boolean,
    amplitude: Float,
    waveform: FloatArray
) {
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )
    val color = if (transmitting) MeshGreen else MeshBlue
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(106.dp)
    ) {
        val barCount = 32
        val gap = size.width * 0.011f
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        for (i in 0 until barCount) {
            val source = if (waveform.isNotEmpty()) waveform[i % waveform.size] else 0.1f
            val idle = 0.08f + 0.05f * abs(sin(phase + i * 0.55f))
            val normalized = if (active) {
                (0.08f + source * (0.35f + amplitude * 0.65f)).coerceIn(0.06f, 1f)
            } else {
                idle
            }
            val h = size.height * normalized
            val x = i * (barWidth + gap)
            val y = (size.height - h) / 2f
            drawRoundRect(
                color = if (active) color else MeshSecondary.copy(alpha = 0.45f),
                topLeft = Offset(x, y),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)
            )
        }
    }
}

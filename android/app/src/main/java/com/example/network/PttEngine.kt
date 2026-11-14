package com.example.network

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Push-to-talk engine: raw PCM16LE mono at 16 kHz over UDP 9999. No codec,
 * no WebRTC, no handshake. Listeners also join 239.255.0.1 like the APK.
 */
class PttEngine(private val context: Context, private val port: Int = PTT_PORT) {
    private val tag = "UdpAudioEngine"
    private val _isListeningEnabled = MutableStateFlow(true)
    val isListeningEnabled: StateFlow<Boolean> = _isListeningEnabled.asStateFlow()
    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting: StateFlow<Boolean> = _isTransmitting.asStateFlow()
    private val _isReceiving = MutableStateFlow(false)
    val isReceiving: StateFlow<Boolean> = _isReceiving.asStateFlow()
    private val _talkingPeer = MutableStateFlow<String?>(null)
    val talkingPeer: StateFlow<String?> = _talkingPeer.asStateFlow()
    private val _audioAmplitude = MutableStateFlow(0f)
    val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()
    private val _audioWaveform = MutableStateFlow(FloatArray(16) { 0.08f })
    val audioWaveform: StateFlow<FloatArray> = _audioWaveform.asStateFlow()

    private var receiveSocket: DatagramSocket? = null
    private var multicastSocket: MulticastSocket? = null
    private var receiveThread: Thread? = null
    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null
    @Volatile private var listening = false
    @Volatile private var speaking = false
    @Volatile private var lastReceiveAt = 0L

    fun setListeningEnabled(enabled: Boolean) {
        _isListeningEnabled.value = enabled
        if (!enabled) {
            _isReceiving.value = false
            _talkingPeer.value = null
            _audioAmplitude.value = 0f
        }
    }

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (listening) return
        listening = true
        try {
            val multicast = MulticastSocket(null)
            multicast.reuseAddress = true
            multicast.bind(InetSocketAddress(port))
            try {
                multicast.timeToLive = 4
            } catch (_: Exception) {
            }
            try {
                multicast.joinGroup(InetAddress.getByName(MULTICAST_IP))
            } catch (e: Exception) {
                Log.w(tag, "Multicast group join failed", e)
            }
            multicastSocket = multicast
            receiveSocket = multicast
        } catch (e: Exception) {
            try {
                receiveSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
            } catch (fallback: Exception) {
                Log.e(tag, "Unicast socket bind failed on $port", fallback)
                listening = false
                return
            }
        }
        initAudioTrack()
        receiveThread = thread(name = "WalkieLowLatencyAudioTrackThread", isDaemon = true) {
            receiveLoop()
        }
    }

    @SuppressLint("MissingPermission")
    fun startSpeaking(targetIpAddresses: List<String>) {
        if (speaking) return
        speaking = true
        _isTransmitting.value = true
        val targets = targetIpAddresses.filter { it.isNotBlank() }.distinct()
        recordThread = thread(name = "WalkieLowLatencyAudioRecordThread", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(1)
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer * 2
                )
            } catch (e: Exception) {
                Log.e(tag, "AudioRecord failed to initialize", e)
                speaking = false
                _isTransmitting.value = false
                return@thread
            }
            audioRecord = record
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(tag, "AudioRecord failed to initialize")
                record.release()
                audioRecord = null
                speaking = false
                _isTransmitting.value = false
                return@thread
            }
            attachEffects(record.audioSessionId)
            val sendSocket = try {
                DatagramSocket().apply {
                    try {
                        broadcast = true
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "PTT send socket failed", e)
                record.release()
                audioRecord = null
                speaking = false
                _isTransmitting.value = false
                return@thread
            }
            // The APK reaches peers through unicast, the multicast group and the
            // subnet/limited broadcast, so a stale peer table never silences PTT.
            val destinations = LinkedHashSet<String>().apply {
                addAll(targets)
                add(MULTICAST_IP)
                add("255.255.255.255")
                addAll(localBroadcastAddresses())
            }
            val addresses = destinations.mapNotNull { ip ->
                try {
                    InetAddress.getByName(ip)
                } catch (_: Exception) {
                    null
                }
            }
            try {
                record.startRecording()
                val buffer = ByteArray(1024)
                while (speaking) {
                    val count = record.read(buffer, 0, buffer.size)
                    if (count <= 0) continue
                    updateLevels(buffer, count)
                    for (address in addresses) {
                        try {
                            sendSocket.send(DatagramPacket(buffer, count, address, port))
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error during walkie-talkie transmission", e)
            } finally {
                try { record.stop() } catch (_: Exception) {}
                record.release()
                sendSocket.close()
                if (audioRecord === record) audioRecord = null
                speaking = false
                _isTransmitting.value = false
                _audioAmplitude.value = 0f
            }
        }
    }

    fun stopSpeaking() {
        speaking = false
        recordThread?.interrupt()
        recordThread = null
        _isTransmitting.value = false
    }

    private fun receiveLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = ByteArray(4096)
        val stopAfterSilence = 1_200L
        while (listening) {
            try {
                val socket = receiveSocket ?: break
                socket.soTimeout = 500
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val count = packet.length
                if (count <= 0) continue
                lastReceiveAt = System.currentTimeMillis()
                val sender = packet.address.hostAddress?.removePrefix("::ffff:")
                _talkingPeer.value = sender
                _isReceiving.value = true
                updateLevels(buffer, count)
                // Do not echo our own transmission back into the speaker.
                if (_isListeningEnabled.value && !speaking) {
                    val track = audioTrack
                    if (track != null) {
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            try { track.play() } catch (_: Exception) {}
                        }
                        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            track.write(buffer, 0, count)
                        }
                    }
                }
            } catch (_: java.net.SocketTimeoutException) {
                if (System.currentTimeMillis() - lastReceiveAt > stopAfterSilence) {
                    _isReceiving.value = false
                    _talkingPeer.value = null
                }
            } catch (e: Exception) {
                if (listening) Log.w(tag, "UDP audio receive error", e)
            }
        }
        _isReceiving.value = false
    }

    private fun localBroadcastAddresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (info in iface.interfaceAddresses) {
                    val broadcast = info.broadcast ?: continue
                    val host = broadcast.hostAddress ?: continue
                    if (!host.startsWith("127.")) result.add(host)
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun initAudioTrack() {
        if (audioTrack != null) return
        val minOut = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(512)
        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            audioTrack = AudioTrack(attributes, format, minOut * 2, AudioTrack.MODE_STREAM, 0)
            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(tag, "Error initializing AudioTrack", e)
        }
    }

    private fun attachEffects(sessionId: Int) {
        if (sessionId == 0) return
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {
        }
    }

    private fun updateLevels(buffer: ByteArray, length: Int) {
        var peak = 0.0
        var sum = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < length) {
            val sample = ((buffer[i].toInt() and 0xff) or (buffer[i + 1].toInt() shl 8)).toShort().toInt()
            val normalized = abs(sample) / 32768.0
            peak = max(peak, normalized)
            sum += normalized * normalized
            samples++
            i += 2
        }
        if (samples == 0) return
        val amplitude = (peak.coerceIn(0.0, 1.0)).toFloat()
        _audioAmplitude.value = amplitude
        val bars = FloatArray(16)
        val perBar = max(1, samples / bars.size)
        var index = 0
        for (bar in bars.indices) {
            var localPeak = 0.0
            var n = 0
            while (n < perBar && index + 1 < length) {
                val sample = ((buffer[index].toInt() and 0xff) or (buffer[index + 1].toInt() shl 8)).toShort().toInt()
                localPeak = max(localPeak, abs(sample) / 32768.0)
                index += 2
                n++
            }
            bars[bar] = (0.06 + localPeak * 0.94).toFloat().coerceIn(0.06f, 1f)
        }
        _audioWaveform.value = bars
    }

    fun stopListening() {
        listening = false
        try { receiveSocket?.close() } catch (_: Exception) {}
        receiveSocket = null
        multicastSocket = null
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
        _isReceiving.value = false
        _talkingPeer.value = null
    }

    fun release() {
        stopSpeaking()
        stopListening()
        try { echoCanceler?.release() } catch (_: Exception) {}
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        try { gainControl?.release() } catch (_: Exception) {}
    }
}

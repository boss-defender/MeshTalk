package com.example.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

data class IceCandidate(
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val candidate: String
)

interface PeerConnectionObserver {
    fun onIceCandidate(candidate: IceCandidate)
    fun onSdpOffer(sdp: String)
    fun onSdpAnswer(sdp: String)
    fun onConnectionStateChange(isConnected: Boolean)
}

/**
 * Direct LAN call media: PCM16 mono 16 kHz on UDP 8890 and JPEG 480x360 q35
 * on UDP 8889. SDP/ICE values are protocol decoration, exactly as in the APK.
 */
class LocalCallEngine(
    private val context: Context,
    private val videoPort: Int = CALL_VIDEO_PORT,
    private val audioPort: Int = CALL_AUDIO_PORT
) {
    private val tag = "LocalCallEngine"
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val iceCandidates = mutableListOf<IceCandidate>()

    private var observer: PeerConnectionObserver? = null
    private var localSdp: String? = null
    private var remoteSdp: String? = null
    @Volatile private var isCallActive = false
    private var targetIp: String = ""
    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var audioSocket: DatagramSocket? = null
    private var videoSocket: DatagramSocket? = null
    private var audioReceiveThread: Thread? = null
    private var audioSendThread: Thread? = null
    private var videoReceiveThread: Thread? = null
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    private val _remoteVideoBitmap = MutableStateFlow<Bitmap?>(null)
    val remoteVideoBitmap: StateFlow<Bitmap?> = _remoteVideoBitmap.asStateFlow()
    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()
    private val _isVideoEnabled = MutableStateFlow(false)
    val isVideoEnabled: StateFlow<Boolean> = _isVideoEnabled.asStateFlow()
    private val _isSpeakerPhoneOn = MutableStateFlow(true)
    val isSpeakerPhoneOn: StateFlow<Boolean> = _isSpeakerPhoneOn.asStateFlow()
    private val _isFrontCamera = MutableStateFlow(true)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    fun setObserver(obs: PeerConnectionObserver) {
        observer = obs
    }

    fun hasRequiredPermissions(): Boolean {
        val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val camera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        return mic && camera
    }

    fun createSdpOffer(targetIp: String): String {
        val sdp = "v=0\r\no=- ${System.currentTimeMillis()} 2 IN IP4 $targetIp\r\n" +
            "s=CallEngine\r\nt=0 0\r\nm=audio $audioPort RTP/AVP 0\r\n" +
            "m=video $videoPort RTP/AVP 26\r\n"
        localSdp = sdp
        observer?.onSdpOffer(sdp)
        val candidate = IceCandidate(
            "0",
            0,
            "candidate:1 1 UDP 2122260223 $targetIp $audioPort typ host"
        )
        iceCandidates.add(candidate)
        observer?.onIceCandidate(candidate)
        return sdp
    }

    fun createSdpAnswer(targetIp: String): String {
        val sdp = "v=0\r\no=- ${System.currentTimeMillis()} 2 IN IP4 $targetIp\r\n" +
            "s=CallEngineAnswer\r\nt=0 0\r\nm=audio $audioPort RTP/AVP 0\r\n" +
            "m=video $videoPort RTP/AVP 26\r\n"
        localSdp = sdp
        observer?.onSdpAnswer(sdp)
        val candidate = IceCandidate(
            "0",
            0,
            "candidate:1 1 UDP 2122260223 $targetIp $audioPort typ host"
        )
        iceCandidates.add(candidate)
        observer?.onIceCandidate(candidate)
        return sdp
    }

    fun handleRemoteSdpOffer(sdp: String) {
        remoteSdp = sdp
        Log.d(tag, "Remote SDP offer received: ${sdp.take(30)}")
    }

    fun handleRemoteSdpAnswer(sdp: String) {
        remoteSdp = sdp
        observer?.onConnectionStateChange(true)
        Log.d(tag, "Remote SDP answer received: ${sdp.take(30)}")
    }

    fun handleRemoteIceCandidate(candidate: IceCandidate) {
        iceCandidates.add(candidate)
        Log.d(tag, "Remote ICE Candidate added: ${candidate.candidate.take(30)}")
    }

    @SuppressLint("MissingPermission")
    fun startCall(targetIp: String, isVideoCall: Boolean, lifecycleOwner: LifecycleOwner) {
        if (isCallActive) return
        this.targetIp = targetIp
        this.lifecycleOwner = lifecycleOwner
        isCallActive = true
        _isVideoEnabled.value = isVideoCall
        if (!hasRequiredPermissions()) {
            Log.w(tag, "Camera or microphone permission not granted before starting call media engine")
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = _isSpeakerPhoneOn.value
        requestAudioFocus()
        bindMediaSockets()
        if (!isCallActive) {
            Log.e(tag, "Call aborted: media sockets unavailable")
            abandonAudioFocus()
            audioManager.mode = AudioManager.MODE_NORMAL
            return
        }
        startAudioLoop()
        startVideoReceiverLoop()
        if (isVideoCall) startCamera()
        observer?.onConnectionStateChange(true)
    }

    private fun requestAudioFocus() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val request =
                    android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(attributes)
                        .setAcceptsDelayedFocusGain(false)
                        .build()
                audioFocusRequest = request
                audioManager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        } catch (e: Exception) {
            Log.w(tag, "Audio focus request failed", e)
        }
    }

    private fun abandonAudioFocus() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (_: Exception) {
        }
        audioFocusRequest = null
    }

    @SuppressLint("MissingPermission")
    private fun bindMediaSockets() {
        try {
            audioSocket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(audioPort))
                soTimeout = 200
                try { receiveBufferSize = 512 * 1024 } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(tag, "Audio socket bind error on $audioPort", e)
        }
        try {
            videoSocket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(videoPort))
                soTimeout = 200
                try { receiveBufferSize = 2 * 1024 * 1024 } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(tag, "Video socket bind error on $videoPort", e)
        }
        if (audioSocket == null) {
            Log.e(tag, "Call cannot continue without the audio socket")
            isCallActive = false
            observer?.onConnectionStateChange(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioLoop() {
        val minOut = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(512)
        val minIn = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(512)
        try {
            audioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                minOut * 2,
                AudioTrack.MODE_STREAM,
                AudioSessionId()
            ).apply { play() }
        } catch (e: Exception) {
            Log.e(tag, "AudioTrack failed to initialize", e)
        }
        audioReceiveThread = thread(name = "CallAudioReceive", isDaemon = true) {
            val socket = audioSocket
            if (socket == null) {
                Log.e(tag, "Audio receive loop aborted: no socket")
                return@thread
            }
            val buffer = ByteArray(4096)
            while (isCallActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val track = audioTrack
                    if (packet.length > 0 && track != null) {
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            try { track.play() } catch (_: Exception) {}
                        }
                        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            track.write(buffer, 0, packet.length)
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                } catch (e: Exception) {
                    if (isCallActive) {
                        Log.w(tag, "Audio recv fail", e)
                        try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                    }
                }
            }
        }
        audioSendThread = thread(name = "CallAudioSend", isDaemon = true) {
            val socket = audioSocket
            if (socket == null) {
                Log.e(tag, "Audio send loop aborted: no socket")
                return@thread
            }
            var recorder: AudioRecord? = null
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minIn * 2
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(tag, "AudioRecord failed to initialize")
                    return@thread
                }
                audioRecord = recorder
                if (AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply {
                        enabled = true
                    }
                }
                val address = InetAddress.getByName(targetIp)
                recorder.startRecording()
                val buffer = ByteArray(1024)
                while (isCallActive) {
                    val count = recorder.read(buffer, 0, buffer.size)
                    if (count > 0 && !_isMicMuted.value) {
                        try {
                            socket.send(DatagramPacket(buffer, count, address, audioPort))
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Audio send fail", e)
            } finally {
                try { recorder?.stop() } catch (_: Exception) {}
                try { recorder?.release() } catch (_: Exception) {}
                if (audioRecord === recorder) audioRecord = null
            }
        }
    }

    private fun startVideoReceiverLoop() {
        videoReceiveThread = thread(name = "CallVideoReceive", isDaemon = true) {
            val socket = videoSocket
            if (socket == null) {
                Log.e(tag, "Video receive loop aborted: no socket")
                return@thread
            }
            val buffer = ByteArray(128 * 1024)
            while (isCallActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    if (packet.length > 0) {
                        val bitmap = BitmapFactory.decodeByteArray(
                            buffer,
                            packet.offset,
                            packet.length
                        )
                        if (bitmap != null) _remoteVideoBitmap.value = bitmap
                    }
                } catch (_: java.net.SocketTimeoutException) {
                } catch (e: Exception) {
                    if (isCallActive) {
                        Log.w(tag, "Video recv fail", e)
                        try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                    }
                }
            }
        }
    }

    private fun startCamera() {
        val owner = lifecycleOwner ?: return
        if (!hasRequiredPermissions()) return
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                bindCameraUseCases(provider, owner)
            } catch (e: Exception) {
                Log.e(tag, "CameraX bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("RestrictedApi")
    private fun bindCameraUseCases(provider: ProcessCameraProvider, owner: LifecycleOwner) {
        provider.unbindAll()
        val selector = if (_isFrontCamera.value) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        val useCases = mutableListOf<androidx.camera.core.UseCase>()
        val preview = previewView
        if (preview != null) {
            useCases.add(
                Preview.Builder().build().also {
                    it.setSurfaceProvider(preview.surfaceProvider)
                }
            )
        }
        if (_isVideoEnabled.value) {
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(480, 360))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                sendVideoFrame(imageProxy)
                imageProxy.close()
            }
            useCases.add(analysis)
        }
        if (useCases.isEmpty()) return
        try {
            provider.bindToLifecycle(owner, selector, *useCases.toTypedArray())
        } catch (e: Exception) {
            Log.e(tag, "CameraX bind failed", e)
        }
    }

    private fun sendVideoFrame(imageProxy: ImageProxy) {
        if (!isCallActive || !_isVideoEnabled.value || targetIp.isBlank()) return
        try {
            val bitmap = imageProxy.toBitmap()
            val scaled = Bitmap.createScaledBitmap(bitmap, 480, 360, true)
            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 35, output)
            val bytes = output.toByteArray()
            val address = InetAddress.getByName(targetIp)
            videoSocket?.send(DatagramPacket(bytes, bytes.size, address, videoPort))
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        } catch (e: Exception) {
            Log.w(tag, "Error encoding video frame", e)
        }
    }

    fun setupLocalPreview(preview: PreviewView, targetIp: String, owner: LifecycleOwner) {
        previewView = preview
        this.targetIp = targetIp
        lifecycleOwner = owner
        startCamera()
    }

    fun toggleMic() {
        _isMicMuted.value = !_isMicMuted.value
    }

    fun toggleSpeaker() {
        _isSpeakerPhoneOn.value = !_isSpeakerPhoneOn.value
        audioManager.isSpeakerphoneOn = _isSpeakerPhoneOn.value
    }

    fun toggleVideo(targetIp: String, owner: LifecycleOwner) {
        this.targetIp = targetIp
        lifecycleOwner = owner
        _isVideoEnabled.value = !_isVideoEnabled.value
        if (_isVideoEnabled.value) {
            startCamera()
        } else {
            cameraProvider?.unbindAll()
        }
    }

    fun switchCamera(targetIp: String, owner: LifecycleOwner) {
        this.targetIp = targetIp
        lifecycleOwner = owner
        _isFrontCamera.value = !_isFrontCamera.value
        val provider = cameraProvider ?: return
        bindCameraUseCases(provider, owner)
    }

    fun endCall() {
        isCallActive = false
        _remoteVideoBitmap.value = null
        _isVideoEnabled.value = false
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        try { audioSocket?.close() } catch (_: Exception) {}
        try { videoSocket?.close() } catch (_: Exception) {}
        audioSocket = null
        videoSocket = null
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { echoCanceler?.release() } catch (_: Exception) {}
        echoCanceler = null
        audioReceiveThread?.interrupt()
        audioSendThread?.interrupt()
        videoReceiveThread?.interrupt()
        audioReceiveThread = null
        audioSendThread = null
        videoReceiveThread = null
        abandonAudioFocus()
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
        observer?.onConnectionStateChange(false)
    }

    private fun AudioSessionId(): Int = 0
}

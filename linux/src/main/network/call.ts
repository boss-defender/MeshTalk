import dgram from 'node:dgram'
import { EventEmitter } from 'node:events'
import { CALL_AUDIO_PORT, CALL_VIDEO_PORT } from '../../shared/types'

export interface CallDiagnostics {
  audioReceived: number
  audioSent: number
  videoReceived: number
  videoSent: number
  lastAudioAt: number
}

/**
 * Local call media transport. Mirrors LocalCallEngine:
 *  - audio: raw PCM16LE mono 16 kHz on UDP 8890 (direct peer)
 *  - video: independently decodable JPEG frames on UDP 8889 (direct peer)
 * Like the original, a single socket per medium both receives on its port and
 * sends to the peer, so the media path needs no extra negotiation.
 * Signalling (CALL_INVITE / ACCEPT / DECLINE / END) travels over the TCP
 * P2P protocol; the SDP/ICE packets in the original app are decorative.
 */
export class CallEngine extends EventEmitter {
  private audioSocket?: dgram.Socket
  private videoSocket?: dgram.Socket
  private peerIp = ''
  private active = false
  private audioReceived = 0
  private audioSent = 0
  private videoReceived = 0
  private videoSent = 0
  private lastAudioAt = 0
  isVideo = false

  start(peerIp: string, isVideo: boolean): void {
    this.stop()
    this.peerIp = peerIp
    this.isVideo = isVideo
    this.active = true

    const audio = dgram.createSocket({ type: 'udp4', reuseAddr: true })
    this.audioSocket = audio
    audio.on('error', (err) => this.emit('error', `call audio: ${err.message}`))
    audio.on('message', (msg) => {
      if (!this.active) return
      this.audioReceived += 1
      this.lastAudioAt = Date.now()
      this.emit('audio', msg)
    })
    try {
      audio.bind(CALL_AUDIO_PORT)
      setTimeout(() => {
        try {
          audio.setRecvBufferSize(512 * 1024)
        } catch {
          /* ignore */
        }
      }, 50)
    } catch (err) {
      this.emit('error', `call audio port ${CALL_AUDIO_PORT} unavailable: ${(err as Error).message}`)
    }

    const video = dgram.createSocket({ type: 'udp4', reuseAddr: true })
    this.videoSocket = video
    video.on('error', (err) => this.emit('error', `call video: ${err.message}`))
    video.on('message', (msg) => {
      if (!this.active) return
      this.videoReceived += 1
      this.emit('video', msg)
    })
    try {
      video.bind(CALL_VIDEO_PORT)
      setTimeout(() => {
        try {
          video.setRecvBufferSize(2 * 1024 * 1024)
        } catch {
          /* ignore */
        }
      }, 50)
    } catch (err) {
      this.emit('error', `call video port ${CALL_VIDEO_PORT} unavailable: ${(err as Error).message}`)
    }
  }

  sendAudio(pcm: Buffer): void {
    if (!this.active || !this.audioSocket || pcm.length === 0 || !this.peerIp) return
    try {
      this.audioSocket.send(pcm, CALL_AUDIO_PORT, this.peerIp, (err) => {
        if (!err) this.audioSent += 1
      })
    } catch {
      /* ignore */
    }
  }

  sendVideo(jpeg: Buffer): void {
    if (!this.active || !this.videoSocket || !this.isVideo || jpeg.length === 0 || !this.peerIp) return
    try {
      this.videoSocket.send(jpeg, CALL_VIDEO_PORT, this.peerIp, (err) => {
        if (!err) this.videoSent += 1
      })
    } catch {
      /* ignore */
    }
  }

  stop(): void {
    this.active = false
    try {
      this.audioSocket?.close()
    } catch {
      /* ignore */
    }
    try {
      this.videoSocket?.close()
    } catch {
      /* ignore */
    }
    this.audioSocket = undefined
    this.videoSocket = undefined
    this.peerIp = ''
  }

  isActive(): boolean {
    return this.active
  }

  diagnostics(): CallDiagnostics {
    return {
      audioReceived: this.audioReceived,
      audioSent: this.audioSent,
      videoReceived: this.videoReceived,
      videoSent: this.videoSent,
      lastAudioAt: this.lastAudioAt
    }
  }
}

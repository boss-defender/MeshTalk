import dgram from 'node:dgram'
import { EventEmitter } from 'node:events'
import { MULTICAST_IP, PTT_PORT } from '../../shared/types'
import { getBroadcastTargets, listInterfaces } from './netinfo'

export interface PttDiagnostics {
  received: number
  sent: number
  lastPacketAt: number
}

/**
 * Push-to-talk audio transport.
 * Mirrors UdpAudioEngine: raw PCM16LE mono @ 16 kHz over UDP 9999.
 * Exactly like the original APK, every frame is sent to the discovered peer
 * unicast addresses AND to the multicast group and limited/subnet broadcast,
 * so a stale or missing peer table never silences the walkie-talkie.
 * Receiving joins 239.255.0.1 on every usable interface with a broadcast
 * fallback.
 */
export class PttEngine extends EventEmitter {
  private socket?: dgram.Socket
  private listening = true
  private running = false
  private lastPacketAt = 0
  private packetsReceived = 0
  private packetsSent = 0

  async start(): Promise<void> {
    if (this.running) return
    this.running = true
    const socket = dgram.createSocket({ type: 'udp4', reuseAddr: true })
    this.socket = socket
    socket.on('error', (err) => {
      this.running = false
      this.emit('error', err.message)
    })
    socket.on('message', (msg) => {
      this.lastPacketAt = Date.now()
      this.packetsReceived += 1
      if (!this.listening) return
      this.emit('audio', msg)
    })
    await new Promise<void>((resolve) => {
      socket.bind(PTT_PORT, () => {
        try {
          socket.setBroadcast(true)
        } catch {
          /* broadcast optional */
        }
        try {
          socket.setMulticastTTL(4)
          socket.setMulticastLoopback(false)
        } catch {
          /* multicast optional */
        }
        const joined = this.joinMulticast(socket)
        if (joined.length > 0) {
          try {
            socket.setMulticastInterface(joined[0])
          } catch {
            /* interface selection optional */
          }
        }
        resolve()
      })
    })
  }

  private joinMulticast(socket: dgram.Socket): string[] {
    const joined: string[] = []
    for (const iface of listInterfaces()) {
      if (iface.internal || iface.address.startsWith('127.') || iface.address.startsWith('169.254.')) continue
      try {
        socket.addMembership(MULTICAST_IP, iface.address)
        joined.push(iface.address)
      } catch {
        /* some adapters reject multicast */
      }
    }
    if (joined.length === 0) {
      try {
        socket.addMembership(MULTICAST_IP)
        joined.push(MULTICAST_IP)
      } catch {
        /* multicast unavailable */
      }
    }
    return joined
  }

  setListening(enabled: boolean): void {
    this.listening = enabled
  }

  isListening(): boolean {
    return this.listening
  }

  isRecentlyReceiving(): boolean {
    return Date.now() - this.lastPacketAt < 700
  }

  diagnostics(): PttDiagnostics {
    return { received: this.packetsReceived, sent: this.packetsSent, lastPacketAt: this.lastPacketAt }
  }

  sendPcm(pcm: Buffer, targetIps: string[]): void {
    const socket = this.socket
    if (!socket || pcm.length === 0) return
    const targets = new Set<string>()
    for (const ip of targetIps) if (ip) targets.add(ip)
    for (const ip of getBroadcastTargets()) targets.add(ip)
    for (const ip of targets) {
      try {
        socket.send(pcm, PTT_PORT, ip, (err) => {
          if (!err) this.packetsSent += 1
        })
      } catch {
        /* ignore unreachable destination */
      }
    }
  }

  stop(): void {
    this.running = false
    try {
      this.socket?.close()
    } catch {
      /* ignore */
    }
    this.socket = undefined
  }
}

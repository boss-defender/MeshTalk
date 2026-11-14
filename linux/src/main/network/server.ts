import dgram from 'node:dgram'
import net from 'node:net'
import fs from 'node:fs'
import path from 'node:path'
import { EventEmitter } from 'node:events'
import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto'
import { pipeline } from 'node:stream/promises'
import { Bonjour, type Browser, type Service } from 'bonjour-service'
import {
  DIRECT_STREAM_KEY,
  createPacket,
  fileHeaderFrame,
  packetFrame,
  packetFromJson
} from './protocol'
import { getBroadcastTargets, getPrimaryIp, getStoragePath, uniqueFilePath } from './netinfo'
import {
  FILE_MAGIC,
  MULTICAST_IP,
  PACKET_MAGIC,
  TCP_PORT,
  UDP_DISCOVERY_PORT,
  type P2PPacket
} from '../../shared/types'

export interface ReceivedFileInfo {
  filePath: string
  fileName: string
  fileSize: number
  senderId: string
  senderName: string
  senderIp: string
  targetId: string
  msgId: string
}

interface Reader {
  read(n: number): Promise<Buffer>
}

export class LocalP2PServer extends EventEmitter {
  private tcpServer?: net.Server
  private udpSocket?: dgram.Socket
  private running = false
  private processed = new Set<string>()
  private bonjour?: Bonjour
  private service?: Service
  private browser?: Browser
  private selfId: () => string
  private selfName: () => string
  mdnsActive = false

  constructor(selfId: () => string, selfName: () => string) {
    super()
    this.selfId = selfId
    this.selfName = selfName
  }

  async start(): Promise<void> {
    if (this.running) return
    this.running = true
    await this.startTcp()
    await this.startUdp()
    this.startMdns()
  }

  private startTcp(): Promise<void> {
    return new Promise((resolve) => {
      const server = net.createServer((socket) => this.handleSocket(socket))
      this.tcpServer = server
      server.once('error', (err: NodeJS.ErrnoException) => {
        this.emit('error', `TCP ${TCP_PORT}: ${err.message}`)
        resolve()
      })
      server.listen(TCP_PORT, '0.0.0.0', () => resolve())
    })
  }

  private startUdp(): Promise<void> {
    return new Promise((resolve) => {
      const socket = dgram.createSocket({ type: 'udp4', reuseAddr: true })
      this.udpSocket = socket
      socket.on('error', (err) => {
        this.emit('error', `UDP ${UDP_DISCOVERY_PORT}: ${err.message}`)
        resolve()
      })
      socket.on('message', (msg, rinfo) => {
        const packet = packetFromJson(msg.toString('utf8'))
        if (!packet) return
        if (packet.senderId === this.selfId()) return
        if (!this.markProcessed(packet.msgId)) return
        this.emit('packet', packet, rinfo.address, 'udp')
      })
      socket.bind(UDP_DISCOVERY_PORT, () => {
        try {
          socket.setBroadcast(true)
        } catch {
          /* ignore */
        }
        try {
          socket.addMembership(MULTICAST_IP)
        } catch {
          /* multicast is optional; broadcast still works */
        }
        resolve()
      })
    })
  }

  private startMdns(): void {
    try {
      this.bonjour = new Bonjour()
      this.publishMdns()
      this.browser = this.bonjour.find({ type: 'walkietalkie' })
      this.browser.on('up', (service: Service) => {
        if (service.name === `WT-${this.selfName()}`) return
        const address = service.addresses?.find((a) => net.isIPv4(a))
        if (!address || address === getPrimaryIp()) return
        this.emit('mdns-peer', {
          name: service.name.replace(/^WT-/, ''),
          ip: address,
          port: service.port || TCP_PORT
        })
      })
      this.mdnsActive = true
    } catch {
      this.mdnsActive = false
    }
  }

  private publishMdns(): void {
    if (!this.bonjour) return
    try {
      this.service = this.bonjour.publish({
        name: `WT-${this.selfName()}`,
        type: 'walkietalkie',
        port: TCP_PORT,
        txt: { app: 'MeshTalk', id: this.selfId() }
      })
    } catch {
      /* ignore */
    }
  }

  updateMdnsName(): void {
    if (!this.bonjour) return
    try {
      this.service?.stop?.()
      this.publishMdns()
    } catch {
      /* ignore */
    }
  }

  private markProcessed(msgId: string): boolean {
    if (!msgId) return true
    if (this.processed.has(msgId)) return false
    this.processed.add(msgId)
    if (this.processed.size > 2000) {
      const first = this.processed.values().next().value
      if (first) this.processed.delete(first)
    }
    return true
  }

  private handleSocket(socket: net.Socket): void {
    const senderIp = socket.remoteAddress?.replace(/^::ffff:/, '') ?? ''
    socket.setNoDelay(true)
    socket.setTimeout(20_000)
    const reader = createReader(socket)
    void (async () => {
      try {
        const markerBuf = await reader.read(1)
        const marker = markerBuf.readUInt8(0)
        const lenBuf = await reader.read(4)
        const len = lenBuf.readInt32BE(0)
        if (len < 1 || len >= 500_001) throw new Error(`Invalid frame length ${len}`)
        const headerJson = (await reader.read(len)).toString('utf8')
        const header = packetFromJson(headerJson)
        if (!header) throw new Error('Invalid JSON header')
        if (marker === PACKET_MAGIC) {
          this.emit('packet', header, senderIp, 'tcp')
        } else if (marker === FILE_MAGIC) {
          socket.setTimeout(30 * 60 * 1000)
          await this.receiveFile(socket, reader, header, senderIp)
        }
      } catch (err) {
        if (this.running) this.emit('error', `connection ${senderIp}: ${(err as Error).message}`)
      } finally {
        socket.destroy()
      }
    })()
  }

  private async receiveFile(
    socket: net.Socket,
    reader: Reader,
    header: P2PPacket,
    senderIp: string
  ): Promise<void> {
    if (header.senderId === this.selfId()) return
    const expected = Number(header.extraData ?? '0') || 0
    const iv = await reader.read(16)
    const decipher = createDecipheriv('aes-256-ctr', DIRECT_STREAM_KEY, iv)
    const dir = getStoragePath()
    const finalPath = uniqueFilePath(dir, header.payload || 'file')
    const tempPath = `${finalPath}.part`
    const out = fs.createWriteStream(tempPath)
    let received = 0
    const transferId = header.msgId
    this.emit('file-progress', {
      id: transferId,
      transferId,
      name: header.payload,
      sentBytes: 0,
      totalBytes: expected,
      direction: 'receive'
    })
    try {
      while (expected > 0 && received < expected) {
        const want = Math.min(64 * 1024, expected - received)
        const encrypted = await reader.read(want)
        const plain = decipher.update(encrypted)
        if (plain.length) out.write(plain)
        received += encrypted.length
        this.emit('file-progress', {
          id: transferId,
          transferId,
          name: header.payload,
          sentBytes: received,
          totalBytes: expected,
          direction: 'receive'
        })
        if (received >= expected) break
      }
      const tail = decipher.final()
      if (tail.length) out.write(tail)
    } catch (err) {
      out.destroy()
      try {
        fs.unlinkSync(tempPath)
      } catch {
        /* ignore */
      }
      throw err
    } finally {
      await new Promise<void>((resolve) => out.end(resolve))
    }
    fs.renameSync(tempPath, finalPath)
    this.emit('file', {
      filePath: finalPath,
      fileName: path.basename(finalPath),
      fileSize: fs.statSync(finalPath).size,
      senderId: header.senderId,
      senderName: header.senderName,
      senderIp,
      targetId: header.targetId,
      msgId: header.msgId
    } satisfies ReceivedFileInfo)
  }

  sendUdp(packet: P2PPacket): void {
    if (!this.udpSocket) return
    const data = Buffer.from(JSON.stringify(withoutNullExtra(packet)), 'utf8')
    for (const target of getBroadcastTargets()) {
      try {
        this.udpSocket.send(data, UDP_DISCOVERY_PORT, target, () => undefined)
      } catch {
        /* ignore */
      }
    }
  }

  sendPacket(targetIp: string, packet: P2PPacket, port = TCP_PORT): Promise<boolean> {
    return new Promise((resolve) => {
      if (!targetIp) return resolve(false)
      const socket = net.createConnection({ host: targetIp, port })
      let settled = false
      const done = (value: boolean) => {
        if (settled) return
        settled = true
        socket.destroy()
        resolve(value)
      }
      socket.setNoDelay(true)
      socket.setTimeout(4000, () => done(false))
      socket.once('error', () => done(false))
      socket.once('connect', () => {
        socket.write(packetFrame(packet), (err) => {
          if (err) done(false)
          else {
            socket.end()
            done(true)
          }
        })
      })
    })
  }

  async sendFile(targetIp: string, header: P2PPacket, filePath: string, port = TCP_PORT): Promise<boolean> {
    if (!targetIp) return false
    const socket = net.createConnection({ host: targetIp, port })
    const transferId = header.msgId
    const total = fs.statSync(filePath).size
    try {
      await new Promise<void>((resolve, reject) => {
        socket.setNoDelay(true)
        socket.setTimeout(30 * 60 * 1000, () => reject(new Error('file transfer timeout')))
        socket.once('error', reject)
        socket.once('connect', resolve)
      })
      socket.write(fileHeaderFrame(header))
      const iv = randomBytes(16)
      socket.write(iv)
      const cipher = createCipheriv('aes-256-ctr', DIRECT_STREAM_KEY, iv)
      let sent = 0
      const input = fs.createReadStream(filePath, { highWaterMark: 64 * 1024 })
      input.on('data', (chunk) => {
        sent += chunk.length
        this.emit('file-progress', {
          id: transferId,
          transferId,
          name: path.basename(filePath),
          sentBytes: sent,
          totalBytes: total,
          direction: 'send'
        })
      })
      await pipeline(input, cipher, socket)
      return true
    } catch {
      socket.destroy()
      return false
    }
  }

  getLocalIp(): string {
    return getPrimaryIp()
  }

  async stop(): Promise<void> {
    this.running = false
    this.browser?.stop?.()
    try {
      this.service?.stop?.()
    } catch {
      /* ignore */
    }
    try {
      this.bonjour?.destroy()
    } catch {
      /* ignore */
    }
    this.udpSocket?.close()
    this.udpSocket = undefined
    if (this.tcpServer) {
      await new Promise<void>((resolve) => this.tcpServer?.close(() => resolve()))
      this.tcpServer = undefined
    }
  }
}

function withoutNullExtra(packet: P2PPacket): Record<string, string> {
  const obj: Record<string, string> = {
    type: packet.type,
    senderId: packet.senderId,
    senderName: packet.senderName,
    targetId: packet.targetId,
    payload: packet.payload,
    msgId: packet.msgId
  }
  if (packet.extraData !== null && packet.extraData !== undefined) obj.extraData = packet.extraData
  return obj
}

function createReader(socket: net.Socket): Reader {
  const iterator = socket[Symbol.asyncIterator]()
  let buffered = Buffer.alloc(0)
  return {
    async read(n: number): Promise<Buffer> {
      while (buffered.length < n) {
        const next = await iterator.next()
        if (next.done) throw new Error('Socket closed before frame completed')
        buffered = buffered.length ? Buffer.concat([buffered, next.value]) : Buffer.from(next.value)
      }
      const out = buffered.subarray(0, n)
      buffered = buffered.subarray(n)
      return Buffer.from(out)
    }
  }
}

export function makePing(selfId: string, selfName: string): P2PPacket {
  return createPacket({
    type: 'PING',
    senderId: selfId,
    senderName: selfName,
    targetId: 'GLOBAL',
    payload: Date.now().toString()
  })
}

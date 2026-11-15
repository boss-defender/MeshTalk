import { randomUUID } from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { app, BrowserWindow, Notification, shell } from 'electron'
import { Store } from './store'
import { LocalP2PServer } from './network/server'
import { PttEngine } from './network/ptt'
import { CallEngine } from './network/call'
import { createPacket, inferMessageType, snippetForType } from './network/protocol'
import {
  getPrimaryIp,
  getStoragePath,
  getUsableLocalIp,
  getWifiSsid
} from './network/netinfo'
import {
  CALL_AUDIO_PORT,
  CALL_VIDEO_PORT,
  MULTICAST_IP,
  PEER_OFFLINE_MS,
  PEER_UNREACHABLE_MS,
  PTT_PORT,
  TCP_PORT,
  UDP_DISCOVERY_PORT,
  type AppEvent,
  type CallInfo,
  type FileProgress,
  type Message,
  type MessageStatus,
  type Peer,
  type Snapshot
} from '../shared/types'

const AVATAR_COLORS = [0xffd0e4ff, 0xffffdbcb, 0xff34d399, 0xfff472b6, 0xffa78bfa, 0xfffbbf24, 0xff60a5fa]

export class Controller {
  readonly store = new Store()
  readonly server: LocalP2PServer
  readonly ptt = new PttEngine()
  readonly call = new CallEngine()

  private window: BrowserWindow | null = null
  private incomingCall: CallInfo | null = null
  private activeCall: CallInfo | null = null
  private pttTransmitting = false
  private heartbeat?: NodeJS.Timeout
  private sweeper?: NodeJS.Timeout
  private lastProgressAt = new Map<string, number>()
  private started = false

  constructor() {
    const settings = this.store.getSettings()
    this.server = new LocalP2PServer(() => this.store.getSettings().deviceId, () => this.store.getSettings().displayName)
    this.wireEvents()
  }

  setWindow(window: BrowserWindow | null): void {
    this.window = window
  }

  async start(): Promise<void> {
    if (this.started) return
    this.started = true
    await this.server.start()
    await this.ptt.start()
    this.ptt.setListening(this.store.getSettings().listeningEnabled)
    this.server.updateMdnsName()
    this.sendPing()
    this.heartbeat = setInterval(() => this.sendPing(), 5000)
    this.sweeper = setInterval(() => this.sweepPeers(), 5000)
    this.store.maybeAutoCleanup()
    this.pushSnapshot()
  }

  async stop(): Promise<void> {
    if (this.heartbeat) clearInterval(this.heartbeat)
    if (this.sweeper) clearInterval(this.sweeper)
    this.ptt.stop()
    this.call.stop()
    await this.server.stop()
    this.store.flush()
  }

  private wireEvents(): void {
    this.server.on('packet', (packet, senderIp, transport) => {
      void this.handlePacket(packet, senderIp, transport)
    })
    this.server.on('file', (info) => {
      const type = inferMessageType(info.fileName)
      const chatId = info.targetId === 'GLOBAL' ? 'GLOBAL' : info.senderIp
      const message: Message = {
        id: info.msgId || randomUUID(),
        senderId: info.senderId,
        senderName: info.senderName || `Peer-${info.senderIp}`,
        chatId,
        content: info.fileName,
        type,
        filePath: info.filePath,
        fileSize: info.fileSize,
        timestamp: Date.now(),
        isMine: false,
        status: 'RECEIVED',
        starred: false
      }
      this.store.addMessage(message)
      this.touchPeer(info.senderIp, info.senderId, info.senderName)
      if (info.targetId !== 'GLOBAL') {
        this.store.patchPeer(info.senderIp, {
          lastMessageTime: Date.now(),
          lastMessageSnippet: `Sent a file: ${info.fileName}`,
          unreadCount: (this.store.getPeer(info.senderIp)?.unreadCount ?? 0) + 1
        })
      }
      this.notify(info.senderName || 'MeshTalk', `File received: ${info.fileName}`)
      this.toast(`Received ${info.fileName}`, 'success')
      this.pushSnapshot()
    })
    this.server.on('file-progress', (progress: FileProgress) => this.emitProgress(progress))
    this.server.on('mdns-peer', ({ name, ip }: { name: string; ip: string }) => {
      this.touchPeer(ip, '', name)
      this.pushSnapshot()
    })
    this.server.on('error', (message: string) => this.toast(message, 'error'))
    this.ptt.on('error', (message: string) => this.toast(`Walkie audio: ${message}`, 'error'))
    this.ptt.on('audio', (pcm: Buffer) => {
      // Half-duplex: ignore incoming walkie audio while we are transmitting.
      // This also suppresses our own broadcast/multicast loopback echo.
      if (this.pttTransmitting) return
      if (!this.store.getSettings().listeningEnabled) return
      this.sendBinary('ptt:audio', pcm)
    })
    this.call.on('audio', (pcm: Buffer) => this.sendBinary('call:audio', pcm))
    this.call.on('video', (jpeg: Buffer) => this.sendBinary('call:video', jpeg))
    this.call.on('error', (message: string) => this.toast(message, 'error'))
  }

  private sendBinary(channel: string, data: Buffer): void {
    if (!this.window || this.window.isDestroyed()) return
    this.window.webContents.send(channel, new Uint8Array(data))
  }

  broadcast(event: AppEvent): void {
    if (!this.window || this.window.isDestroyed()) return
    this.window.webContents.send('app:event', event)
  }

  toast(message: string, tone: 'info' | 'success' | 'error' = 'info'): void {
    this.broadcast({ type: 'toast', message, tone })
  }

  private emitProgress(progress: FileProgress): void {
    const now = Date.now()
    const last = this.lastProgressAt.get(progress.transferId) ?? 0
    const done = progress.totalBytes > 0 && progress.sentBytes >= progress.totalBytes
    if (!done && now - last < 120) return
    this.lastProgressAt.set(progress.transferId, now)
    if (done) this.lastProgressAt.delete(progress.transferId)
    this.broadcast({ type: 'file-progress', progress })
  }

  private notify(title: string, body: string): void {
    try {
      if (this.window?.isFocused()) return
      if (!Notification.isSupported()) return
      const notification = new Notification({ title, body })
      notification.on('click', () => {
        this.window?.show()
        this.window?.focus()
      })
      notification.show()
    } catch {
      /* ignore */
    }
  }

  snapshot(): Snapshot {
    const settings = this.store.getSettings()
    const ip = getPrimaryIp()
    const usableIp = getUsableLocalIp()
    const peers = this.store.getPeers()
    const online = peers.filter((p) => p.isOnline)
    const ptt = this.ptt.diagnostics()
    const call = this.call.diagnostics()
    return {
      settings,
      peers,
      messages: this.store.getMessages(),
      network: {
        localIp: usableIp || ip,
        wifiSsid: getWifiSsid(),
        tcpPort: TCP_PORT,
        udpPort: UDP_DISCOVERY_PORT,
        pttPort: PTT_PORT,
        callAudioPort: CALL_AUDIO_PORT,
        callVideoPort: CALL_VIDEO_PORT,
        multicastGroup: MULTICAST_IP,
        mdns: this.server.mdnsActive,
        multicastLock: this.ptt.isListening(),
        storagePath: getStoragePath(),
        pttReceived: ptt.received,
        pttSent: ptt.sent,
        callAudioReceived: call.audioReceived,
        callAudioSent: call.audioSent
      },
      incomingCall: this.incomingCall,
      activeCall: this.activeCall,
      connectionStatus: online.length > 0 ? 'CONNECTED' : usableIp ? 'SEARCHING' : 'DISCONNECTED'
    }
  }

  pushSnapshot(): void {
    this.broadcast({ type: 'snapshot', snapshot: this.snapshot() })
  }

  private touchPeer(ip: string, senderId: string, senderName: string, rtt = -1): void {
    if (!ip) return
    const existing = this.store.getPeer(ip)
    const name = senderName || existing?.displayName || `Peer-${ip}`
    const peer: Peer = {
      id: ip,
      displayName: name,
      ipAddress: ip,
      port: TCP_PORT,
      lastSeen: Date.now(),
      isOnline: true,
      rttMs: rtt >= 0 ? rtt : existing?.rttMs ?? -1,
      unreadCount: existing?.unreadCount ?? 0,
      lastMessageTime: existing?.lastMessageTime ?? 0,
      lastMessageSnippet: existing?.lastMessageSnippet ?? null,
      avatarColor: existing?.avatarColor ?? AVATAR_COLORS[hash(ip) % AVATAR_COLORS.length]
    }
    // If a peer changed its name, drop stale entries with the same name from other IPs.
    if (senderName) {
      for (const other of this.store.getPeers()) {
        if (other.ipAddress !== ip && other.displayName === senderName) {
          this.store.patchPeer(other.ipAddress, { isOnline: false })
        }
      }
    }
    const changed =
      !existing ||
      existing.displayName !== peer.displayName ||
      !existing.isOnline ||
      existing.rttMs !== peer.rttMs ||
      Math.abs(existing.lastSeen - peer.lastSeen) > 60_000
    this.store.upsertPeer(peer)
    if (changed) this.pushSnapshot()
  }

  private sweepPeers(): void {
    const now = Date.now()
    let changed = false
    for (const peer of this.store.getPeers()) {
      if (!peer.isOnline) continue
      const age = now - peer.lastSeen
      if (age > PEER_OFFLINE_MS) {
        this.store.patchPeer(peer.ipAddress, { isOnline: false })
        changed = true
      } else if (age > PEER_UNREACHABLE_MS) {
        this.store.patchPeer(peer.ipAddress, { rttMs: -1 })
      }
    }
    if (changed) this.pushSnapshot()
  }

  private sendPing(): void {
    const settings = this.store.getSettings()
    const ping = createPacket({
      type: 'PING',
      senderId: settings.deviceId,
      senderName: settings.displayName,
      targetId: 'GLOBAL',
      payload: Date.now().toString()
    })
    this.server.sendUdp(ping)
    for (const peer of this.store.getPeers()) {
      if (peer.isOnline) void this.server.sendPacket(peer.ipAddress, ping)
    }
  }

  private async handlePacket(packet: import('../shared/types').P2PPacket, senderIp: string, transport: string): Promise<void> {
    if (!packet || packet.senderId === this.store.getSettings().deviceId) return
    this.touchPeer(senderIp, packet.senderId, packet.senderName)
    switch (packet.type) {
      case 'CHAT_MSG':
        this.handleChatMessage(packet, senderIp)
        break
      case 'MSG_ACK':
        this.store.updateStatus(packet.payload, 'DELIVERED')
        this.pushSnapshot()
        break
      case 'MSG_READ':
        this.store.markOutgoingRead(senderIp)
        this.pushSnapshot()
        break
      case 'PING': {
        const pong = createPacket({
          type: 'PONG',
          senderId: this.store.getSettings().deviceId,
          senderName: this.store.getSettings().displayName,
          targetId: packet.senderId,
          payload: packet.payload
        })
        await this.server.sendPacket(senderIp, pong)
        break
      }
      case 'PONG': {
        const sentAt = Number(packet.payload)
        const rtt = Number.isFinite(sentAt) ? Math.max(0, Date.now() - sentAt) : -1
        this.touchPeer(senderIp, packet.senderId, packet.senderName, rtt)
        break
      }
      case 'CALL_INVITE':
        this.handleCallInvite(packet, senderIp)
        break
      case 'CALL_ACCEPT':
        this.handleCallAccept(packet, senderIp)
        break
      case 'CALL_DECLINE':
        if (this.activeCall?.peerIp === senderIp || this.incomingCall?.peerIp === senderIp) {
          this.clearCalls()
          this.toast('Call declined', 'info')
        }
        break
      case 'CALL_END':
        if (this.activeCall?.peerIp === senderIp || this.incomingCall?.peerIp === senderIp) {
          this.clearCalls()
          this.toast('Call ended', 'info')
        }
        break
      case 'SDP_OFFER':
      case 'SDP_ANSWER':
      case 'ICE_CANDIDATE':
        break
      default:
        break
    }
  }

  private handleChatMessage(packet: import('../shared/types').P2PPacket, senderIp: string): void {
    const chatId = packet.targetId === 'GLOBAL' ? 'GLOBAL' : senderIp
    const existing = this.store.getMessages().find((m) => m.id === packet.msgId)
    if (!existing) {
      const message: Message = {
        id: packet.msgId,
        senderId: packet.senderId,
        senderName: packet.senderName || `Peer-${senderIp}`,
        chatId,
        content: packet.payload,
        type: 'TEXT',
        filePath: null,
        fileSize: 0,
        timestamp: Date.now(),
        isMine: false,
        status: 'RECEIVED',
        starred: false
      }
      this.store.addMessage(message)
      if (chatId !== 'GLOBAL') {
        const peer = this.store.getPeer(senderIp)
        this.store.patchPeer(senderIp, {
          lastMessageTime: Date.now(),
          lastMessageSnippet: packet.payload.slice(0, 80),
          unreadCount: (peer?.unreadCount ?? 0) + 1
        })
      }
      this.notify(packet.senderName || 'MeshTalk', packet.payload)
    }
    const ack = createPacket({
      type: 'MSG_ACK',
      senderId: this.store.getSettings().deviceId,
      senderName: this.store.getSettings().displayName,
      targetId: packet.senderId,
      payload: packet.msgId
    })
    void this.server.sendPacket(senderIp, ack)
    this.pushSnapshot()
  }

  private isUsablePeerIp(ip: string): boolean {
    if (!/^\d+\.\d+\.\d+\.\d+$/.test(ip)) return false
    if (ip.startsWith('127.') || ip === '0.0.0.0') return false
    if (ip.startsWith('169.254.')) return false
    return true
  }

  private handleCallInvite(packet: import('../shared/types').P2PPacket, senderIp: string): void {
    if (this.activeCall || this.incomingCall) {
      const decline = createPacket({
        type: 'CALL_DECLINE',
        senderId: this.store.getSettings().deviceId,
        senderName: this.store.getSettings().displayName,
        targetId: senderIp
      })
      void this.server.sendPacket(senderIp, decline)
      return
    }
    // The invite payload carries the caller's advertised IP. Trust it only when
    // it is a routable address; otherwise fall back to the TCP sender address.
    const advertised = packet.payload
    const callerIp = this.isUsablePeerIp(advertised) ? advertised : senderIp
    this.incomingCall = {
      id: randomUUID(),
      peerIp: callerIp,
      peerName: packet.senderName || `Peer-${callerIp}`,
      isVideo: packet.extraData === 'true',
      direction: 'incoming',
      state: 'ringing',
      startedAt: Date.now()
    }
    this.touchPeer(callerIp, packet.senderId, packet.senderName)
    this.notify(packet.senderName || 'Incoming call', this.incomingCall.isVideo ? 'Incoming video call' : 'Incoming voice call')
    this.toast(this.incomingCall.isVideo ? 'Incoming video call' : 'Incoming voice call', 'info')
    this.pushSnapshot()
  }

  private handleCallAccept(packet: import('../shared/types').P2PPacket, senderIp: string): void {
    if (!this.activeCall && this.incomingCall?.peerIp === senderIp) {
      this.activeCall = { ...this.incomingCall, state: 'active', startedAt: Date.now() }
      this.incomingCall = null
      this.call.start(this.activeCall.peerIp, this.activeCall.isVideo)
      this.pushSnapshot()
      return
    }
    if (this.activeCall?.peerIp === senderIp && this.activeCall.state === 'ringing') {
      this.activeCall = { ...this.activeCall, state: 'active', startedAt: Date.now() }
      this.call.start(this.activeCall.peerIp, this.activeCall.isVideo)
      this.pushSnapshot()
    }
  }

  private clearCalls(): void {
    this.activeCall = null
    this.incomingCall = null
    this.call.stop()
    this.pushSnapshot()
  }

  // ---- actions invoked from the renderer ----

  updateDisplayName(name: string): void {
    const trimmed = name.trim().slice(0, 40)
    if (!trimmed) return
    this.store.updateSettings({ displayName: trimmed })
    this.server.updateMdnsName()
    this.pushSnapshot()
    this.toast('✓ Nickname updated', 'success')
  }

  setOledTheme(enabled: boolean): void {
    this.store.updateSettings({ oledTheme: enabled })
    this.pushSnapshot()
  }

  setAutoDelete(enabled: boolean): void {
    this.store.updateSettings({ autoDeleteOldMessages: enabled })
    this.pushSnapshot()
  }

  cleanUpNow(): number {
    const removed = this.store.cleanupOldMessages(2)
    this.pushSnapshot()
    return removed
  }

  setChannel(channel: number): void {
    this.store.updateSettings({ channel: Math.max(1, Math.min(99, channel)) })
    this.pushSnapshot()
  }

  setListening(enabled: boolean): void {
    this.ptt.setListening(enabled)
    this.store.updateSettings({ listeningEnabled: enabled })
    this.pushSnapshot()
  }

  async sendText(chatId: string, content: string): Promise<void> {
    const text = content.trim()
    if (!text) return
    const settings = this.store.getSettings()
    const messageId = randomUUID()
    const message: Message = {
      id: messageId,
      senderId: settings.deviceId,
      senderName: settings.displayName,
      chatId,
      content: text,
      type: 'TEXT',
      filePath: null,
      fileSize: 0,
      timestamp: Date.now(),
      isMine: true,
      status: 'SENDING',
      starred: false
    }
    this.store.addMessage(message)
    this.pushSnapshot()
    const packet = createPacket({
      type: 'CHAT_MSG',
      senderId: settings.deviceId,
      senderName: settings.displayName,
      targetId: chatId,
      payload: text,
      msgId: messageId
    })
    let success = false
    if (chatId === 'GLOBAL') {
      this.server.sendUdp(packet)
      await Promise.all(
        this.store.getPeers().filter((p) => p.isOnline).map((p) => this.server.sendPacket(p.ipAddress, packet))
      )
      // UDP broadcast is fire-and-forget; an empty subnet is still a valid broadcast.
      success = true
    } else {
      const peer = this.store.getPeer(chatId)
      const ip = peer?.ipAddress ?? chatId
      success = await this.server.sendPacket(ip, packet)
    }
    this.store.updateStatus(messageId, success ? 'SENT' : 'FAILED')
    if (chatId !== 'GLOBAL') {
      const peer = this.store.getPeer(chatId)
      this.store.patchPeer(chatId, {
        lastMessageTime: message.timestamp,
        lastMessageSnippet: text.slice(0, 80),
        unreadCount: peer?.unreadCount ?? 0
      })
    }
    if (!success) this.toast('Message could not be delivered', 'error')
    this.pushSnapshot()
  }

  async sendFile(chatId: string): Promise<void> {
    if (!this.window) return
    const { dialog } = await import('electron')
    const result = await dialog.showOpenDialog(this.window, {
      title: 'Attach file',
      properties: ['openFile']
    })
    if (result.canceled || result.filePaths.length === 0) return
    await this.sendFilePath(chatId, result.filePaths[0])
  }

  async sendFilePath(chatId: string, filePath: string): Promise<void> {
    const settings = this.store.getSettings()
    const stat = fs.statSync(filePath)
    const type = inferMessageType(filePath)
    const messageId = randomUUID()
    const message: Message = {
      id: messageId,
      senderId: settings.deviceId,
      senderName: settings.displayName,
      chatId,
      content: path.basename(filePath),
      type,
      filePath,
      fileSize: stat.size,
      timestamp: Date.now(),
      isMine: true,
      status: 'SENDING',
      starred: false
    }
    this.store.addMessage(message)
    if (chatId !== 'GLOBAL') {
      const peer = this.store.getPeer(chatId)
      this.store.patchPeer(chatId, {
        lastMessageTime: message.timestamp,
        lastMessageSnippet: snippetForType(type),
        unreadCount: peer?.unreadCount ?? 0
      })
    }
    this.pushSnapshot()
    const header = createPacket({
      type: 'FILE_HEADER',
      senderId: settings.deviceId,
      senderName: settings.displayName,
      targetId: chatId,
      payload: path.basename(filePath),
      extraData: String(stat.size),
      msgId: messageId
    })
    const recipients =
      chatId === 'GLOBAL'
        ? this.store.getPeers().filter((p) => p.isOnline).map((p) => p.ipAddress)
        : [this.store.getPeer(chatId)?.ipAddress ?? chatId]
    let success = chatId === 'GLOBAL'
    for (const ip of recipients) {
      const ok = await this.server.sendFile(ip, header, filePath)
      success = success || ok
    }
    this.store.updateStatus(messageId, success ? 'SENT' : 'FAILED')
    if (!success) this.toast('File could not be delivered', 'error')
    this.pushSnapshot()
  }

  async sendVoiceNote(chatId: string, wav: ArrayBuffer): Promise<void> {
    const settings = this.store.getSettings()
    const dir = path.join(app.getPath('userData'), 'voice-notes')
    fs.mkdirSync(dir, { recursive: true })
    const filePath = path.join(dir, `voice-${Date.now()}.wav`)
    fs.writeFileSync(filePath, Buffer.from(wav))
    try {
      await this.sendFilePath(chatId, filePath)
    } finally {
      try {
        fs.unlinkSync(filePath)
      } catch {
        /* ignore */
      }
    }
  }

  async connectManual(ip: string): Promise<boolean> {
    const target = ip.trim()
    if (!/^\d+\.\d+\.\d+\.\d+$/.test(target)) return false
    const ping = createPacket({
      type: 'PING',
      senderId: this.store.getSettings().deviceId,
      senderName: this.store.getSettings().displayName,
      targetId: 'GLOBAL',
      payload: Date.now().toString()
    })
    const ok = await this.server.sendPacket(target, ping)
    if (ok) {
      this.touchPeer(target, '', `Peer-${target}`)
      this.toast(`Contacted ${target}`, 'success')
    } else {
      this.toast(`Could not reach ${target}`, 'error')
    }
    this.pushSnapshot()
    return ok
  }

  markChatRead(chatId: string): void {
    if (!chatId || chatId === 'GLOBAL') return
    this.store.markChatRead(chatId)
    const read = createPacket({
      type: 'MSG_READ',
      senderId: this.store.getSettings().deviceId,
      senderName: this.store.getSettings().displayName,
      targetId: chatId,
      payload: chatId
    })
    const peer = this.store.getPeer(chatId)
    if (peer) void this.server.sendPacket(peer.ipAddress, read)
    this.pushSnapshot()
  }

  toggleStar(messageId: string, starred: boolean): void {
    this.store.toggleStar(messageId, starred)
    this.pushSnapshot()
  }

  startPtt(): void {
    this.pttTransmitting = true
  }

  stopPtt(): void {
    this.pttTransmitting = false
  }

  sendPttAudio(pcm: ArrayBuffer): void {
    if (!this.pttTransmitting) return
    const targets = this.store.getPeers().filter((p) => p.isOnline).map((p) => p.ipAddress)
    this.ptt.sendPcm(Buffer.from(pcm), targets)
  }

  sendCallAudio(pcm: ArrayBuffer): void {
    this.call.sendAudio(Buffer.from(pcm))
  }

  sendCallVideo(jpeg: ArrayBuffer): void {
    this.call.sendVideo(Buffer.from(jpeg))
  }

  async initiateCall(peerIp: string, isVideo: boolean): Promise<void> {
    if (!peerIp) return
    if (this.activeCall || this.incomingCall) {
      this.toast('Another call is already in progress', 'error')
      return
    }
    const peer = this.store.getPeer(peerIp)
    this.activeCall = {
      id: randomUUID(),
      peerIp,
      peerName: peer?.displayName ?? `Peer-${peerIp}`,
      isVideo,
      direction: 'outgoing',
      state: 'ringing',
      startedAt: Date.now()
    }
    const settings = this.store.getSettings()
    const invite = createPacket({
      type: 'CALL_INVITE',
      senderId: settings.deviceId,
      senderName: settings.displayName,
      targetId: peerIp,
      payload: getUsableLocalIp() || getPrimaryIp(),
      extraData: String(isVideo)
    })
    const sent = await this.server.sendPacket(peerIp, invite)
    if (!sent) {
      this.clearCalls()
      this.toast('Could not reach peer', 'error')
      return
    }
    this.pushSnapshot()
  }

  async acceptCall(): Promise<void> {
    const call = this.incomingCall
    if (!call) return
    this.activeCall = { ...call, state: 'active', startedAt: Date.now() }
    this.incomingCall = null
    const settings = this.store.getSettings()
    const accept = createPacket({
      type: 'CALL_ACCEPT',
      senderId: settings.deviceId,
      senderName: settings.displayName,
      targetId: call.peerIp,
      payload: getUsableLocalIp() || getPrimaryIp(),
      extraData: String(call.isVideo)
    })
    await this.server.sendPacket(call.peerIp, accept)
    this.call.start(call.peerIp, call.isVideo)
    this.pushSnapshot()
  }

  async declineCall(): Promise<void> {
    const call = this.incomingCall
    if (!call) return
    this.incomingCall = null
    const settings = this.store.getSettings()
    const decline = createPacket({
      type: 'CALL_DECLINE',
      senderId: settings.deviceId,
      senderName: settings.displayName,
      targetId: call.peerIp
    })
    await this.server.sendPacket(call.peerIp, decline)
    this.pushSnapshot()
  }

  async endCall(): Promise<void> {
    const call = this.activeCall ?? this.incomingCall
    if (!call) return
    this.activeCall = null
    this.incomingCall = null
    this.call.stop()
    const settings = this.store.getSettings()
    const end = createPacket({
      type: 'CALL_END',
      senderId: settings.deviceId,
      senderName: settings.displayName,
      targetId: call.peerIp
    })
    await this.server.sendPacket(call.peerIp, end)
    this.pushSnapshot()
  }

  async openFile(filePath: string): Promise<void> {
    try {
      await shell.openPath(filePath)
    } catch {
      /* ignore */
    }
  }

  showInFolder(filePath: string): void {
    try {
      shell.showItemInFolder(filePath)
    } catch {
      /* ignore */
    }
  }

  openDownloadFolder(): void {
    void shell.openPath(getStoragePath())
  }
}

function hash(value: string): number {
  let h = 0
  for (let i = 0; i < value.length; i++) h = (h * 31 + value.charCodeAt(i)) | 0
  return Math.abs(h)
}

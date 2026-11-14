import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { app } from 'electron'
import { randomUUID } from 'node:crypto'
import type { Message, MessageStatus, Peer, Settings } from '../shared/types'

interface StoreData {
  version: number
  settings: Settings
  peers: Record<string, Peer>
  messages: Message[]
}

const TWO_DAYS = 2 * 24 * 60 * 60 * 1000

export class Store {
  private data: StoreData
  private file: string
  private saveTimer?: NodeJS.Timeout

  constructor() {
    const dir = safeUserData()
    this.file = path.join(dir, 'meshtalk-store.json')
    this.data = this.load()
  }

  private load(): StoreData {
    const fallback: StoreData = {
      version: 1,
      settings: {
        displayName: defaultName(),
        deviceId: `${os.hostname().replace(/[^a-zA-Z0-9_-]/g, '') || 'Linux'}_${randomUUID().slice(0, 4)}`,
        oledTheme: false,
        autoDeleteOldMessages: false,
        listeningEnabled: true,
        channel: 1
      },
      peers: {},
      messages: []
    }
    try {
      const raw = fs.readFileSync(this.file, 'utf8')
      const parsed = JSON.parse(raw) as Partial<StoreData>
      return {
        version: 1,
        settings: { ...fallback.settings, ...(parsed.settings ?? {}) },
        peers: parsed.peers ?? {},
        messages: Array.isArray(parsed.messages) ? parsed.messages : []
      }
    } catch {
      return fallback
    }
  }

  getSettings(): Settings {
    return this.data.settings
  }

  updateSettings(patch: Partial<Settings>): Settings {
    this.data.settings = { ...this.data.settings, ...patch }
    this.save()
    return this.data.settings
  }

  getPeers(): Peer[] {
    return Object.values(this.data.peers).sort((a, b) => {
      const aTime = a.lastMessageTime || a.lastSeen
      const bTime = b.lastMessageTime || b.lastSeen
      return bTime - aTime
    })
  }

  getPeer(id: string): Peer | undefined {
    return this.data.peers[id]
  }

  upsertPeer(peer: Peer): Peer {
    const existing = this.data.peers[peer.id]
    this.data.peers[peer.id] = { ...existing, ...peer }
    this.save()
    return this.data.peers[peer.id]
  }

  patchPeer(id: string, patch: Partial<Peer>): Peer | undefined {
    const existing = this.data.peers[id]
    if (!existing) return undefined
    this.data.peers[id] = { ...existing, ...patch }
    this.save()
    return this.data.peers[id]
  }

  getMessages(): Message[] {
    return [...this.data.messages].sort((a, b) => a.timestamp - b.timestamp)
  }

  getMessagesForChat(chatId: string): Message[] {
    return this.getMessages().filter((m) => m.chatId === chatId)
  }

  addMessage(message: Message): Message {
    if (message.id && this.data.messages.some((m) => m.id === message.id)) {
      return message
    }
    this.data.messages.push(message)
    if (this.data.messages.length > 8000) {
      const starred = this.data.messages.filter((m) => m.starred)
      const rest = this.data.messages.filter((m) => !m.starred).slice(-7000)
      this.data.messages = [...rest, ...starred].sort((a, b) => a.timestamp - b.timestamp)
    }
    this.save()
    return message
  }

  updateMessage(id: string, patch: Partial<Message>): Message | undefined {
    const message = this.data.messages.find((m) => m.id === id)
    if (!message) return undefined
    Object.assign(message, patch)
    this.save()
    return message
  }

  updateStatus(id: string, status: MessageStatus): void {
    const message = this.data.messages.find((m) => m.id === id)
    if (message && message.isMine) {
      message.status = status
      this.save()
    }
  }

  markChatRead(chatId: string): void {
    for (const message of this.data.messages) {
      if (message.chatId === chatId && !message.isMine && message.status !== 'READ') {
        message.status = 'READ'
      }
    }
    const peer = this.data.peers[chatId]
    if (peer) peer.unreadCount = 0
    this.save()
  }

  markOutgoingRead(chatId: string): void {
    for (const message of this.data.messages) {
      if (message.chatId === chatId && message.isMine) message.status = 'READ'
    }
    this.save()
  }

  toggleStar(id: string, starred: boolean): void {
    const message = this.data.messages.find((m) => m.id === id)
    if (message) {
      message.starred = starred
      this.save()
    }
  }

  cleanupOldMessages(days = 2): number {
    const cutoff = Date.now() - days * 24 * 60 * 60 * 1000
    const before = this.data.messages.length
    this.data.messages = this.data.messages.filter((m) => m.starred || m.timestamp >= cutoff)
    this.save()
    return before - this.data.messages.length
  }

  maybeAutoCleanup(): number {
    if (!this.data.settings.autoDeleteOldMessages) return 0
    const cutoff = Date.now() - TWO_DAYS
    const before = this.data.messages.length
    this.data.messages = this.data.messages.filter((m) => m.starred || m.timestamp >= cutoff)
    if (this.data.messages.length !== before) this.save()
    return before - this.data.messages.length
  }

  save(): void {
    if (this.saveTimer) clearTimeout(this.saveTimer)
    this.saveTimer = setTimeout(() => this.flush(), 200)
  }

  flush(): void {
    if (this.saveTimer) {
      clearTimeout(this.saveTimer)
      this.saveTimer = undefined
    }
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true })
      const temp = `${this.file}.tmp`
      fs.writeFileSync(temp, JSON.stringify(this.data), 'utf8')
      fs.renameSync(temp, this.file)
    } catch {
      /* ignore disk errors */
    }
  }
}

function defaultName(): string {
  return `Walkie-User-${Math.floor(1000 + Math.random() * 9000)}`
}

function safeUserData(): string {
  try {
    return app.getPath('userData')
  } catch {
    return path.join(os.homedir(), '.config', 'meshtalk')
  }
}

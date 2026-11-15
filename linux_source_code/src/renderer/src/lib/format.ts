import type { Message, MessageType, Peer } from '../../../shared/types'

export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const exponent = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)))
  const value = bytes / Math.pow(1024, exponent)
  return `${value >= 10 || exponent === 0 ? Math.round(value) : value.toFixed(1)} ${units[exponent]}`
}

export function formatClock(timestamp: number): string {
  return new Date(timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

export function formatRelative(timestamp: number): string {
  if (!timestamp) return ''
  const delta = Date.now() - timestamp
  if (delta < 45_000) return 'now'
  if (delta < 3_600_000) return `${Math.max(1, Math.round(delta / 60_000))}m ago`
  const date = new Date(timestamp)
  const today = new Date()
  if (date.toDateString() === today.toDateString()) return formatClock(timestamp)
  const yesterday = new Date(today)
  yesterday.setDate(today.getDate() - 1)
  if (date.toDateString() === yesterday.toDateString()) return 'Yesterday'
  return date.toLocaleDateString([], { month: 'short', day: 'numeric' })
}

export function formatDuration(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  return `${minutes}:${seconds.toString().padStart(2, '0')}`
}

export function mediaUrl(filePath?: string | null): string | undefined {
  if (!filePath) return undefined
  return `media://file?path=${encodeURIComponent(filePath)}`
}

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase()
  return `${parts[0][0]}${parts[parts.length - 1][0]}`.toUpperCase()
}

export function avatarColor(value: number | undefined): string {
  if (!value) return '#8FB8E8'
  const hex = (value & 0xffffff).toString(16).padStart(6, '0')
  return `#${hex}`
}

export function peerForChat(peers: Peer[], chatId: string | null): Peer | undefined {
  if (!chatId || chatId === 'GLOBAL') return undefined
  return peers.find((peer) => peer.ipAddress === chatId || peer.id === chatId)
}

export function chatTitle(peers: Peer[], chatId: string | null): string {
  if (!chatId) return ''
  if (chatId === 'GLOBAL') return 'Broadcast Hub'
  return peerForChat(peers, chatId)?.displayName ?? chatId
}

export function messagePreview(message: Message): string {
  switch (message.type) {
    case 'IMAGE':
      return `${message.isMine ? 'You sent' : 'Sent'} a photo`
    case 'VIDEO':
      return `${message.isMine ? 'You sent' : 'Sent'} a video`
    case 'VOICE_NOTE':
      return `${message.isMine ? 'You sent' : 'Sent'} a voice note`
    case 'FILE':
      return `${message.isMine ? 'You sent' : 'Sent'} a file`
    default:
      return message.content
  }
}

export function typeIcon(type: MessageType): string {
  switch (type) {
    case 'IMAGE':
      return 'image'
    case 'VIDEO':
      return 'video'
    case 'VOICE_NOTE':
      return 'voice'
    case 'FILE':
      return 'file'
    default:
      return 'text'
  }
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

export function downloadStatusLabel(status: Message['status']): string {
  switch (status) {
    case 'SENDING':
      return 'Sending…'
    case 'SENT':
      return 'Sent'
    case 'DELIVERED':
      return 'Delivered'
    case 'READ':
      return 'Seen'
    case 'FAILED':
      return 'Failed'
    default:
      return 'Received'
  }
}

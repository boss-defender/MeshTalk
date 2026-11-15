import { randomUUID } from 'node:crypto'
import { PACKET_MAGIC, FILE_MAGIC, type MessageType, type P2PPacket, type SignalType } from '../../shared/types'

export const DIRECT_STREAM_KEY = Buffer.concat([
  Buffer.from('P2P_TACTICAL_DIRECT_BINARY_KEY', 'utf8'),
  Buffer.alloc(32)
]).subarray(0, 32)

export interface NewPacket {
  type: SignalType
  senderId: string
  senderName: string
  targetId?: string
  payload?: string
  extraData?: string | null
  msgId?: string
}

export function createPacket(p: NewPacket): P2PPacket {
  return {
    type: p.type,
    senderId: p.senderId,
    senderName: p.senderName,
    targetId: p.targetId ?? 'GLOBAL',
    payload: p.payload ?? '',
    msgId: p.msgId ?? randomUUID(),
    extraData: p.extraData ?? null
  }
}

export function packetToJson(packet: P2PPacket): string {
  const obj: Record<string, string> = {
    type: packet.type,
    senderId: packet.senderId,
    senderName: packet.senderName,
    targetId: packet.targetId,
    payload: packet.payload,
    msgId: packet.msgId
  }
  if (packet.extraData !== null && packet.extraData !== undefined) {
    obj.extraData = packet.extraData
  }
  return JSON.stringify(obj)
}

export function packetFromJson(text: string): P2PPacket | null {
  try {
    const value = JSON.parse(text) as Partial<P2PPacket>
    if (!value || typeof value.type !== 'string') return null
    return {
      type: value.type as SignalType,
      senderId: String(value.senderId ?? ''),
      senderName: String(value.senderName ?? ''),
      targetId: String(value.targetId ?? 'GLOBAL'),
      payload: String(value.payload ?? ''),
      msgId: String(value.msgId ?? randomUUID()),
      extraData: value.extraData === undefined || value.extraData === null ? null : String(value.extraData)
    }
  } catch {
    return null
  }
}

export function packetFrame(packet: P2PPacket): Buffer {
  const json = Buffer.from(packetToJson(packet), 'utf8')
  const frame = Buffer.allocUnsafe(5 + json.length)
  frame.writeUInt8(PACKET_MAGIC, 0)
  frame.writeInt32BE(json.length, 1)
  json.copy(frame, 5)
  return frame
}

export function fileHeaderFrame(header: P2PPacket): Buffer {
  const json = Buffer.from(packetToJson(header), 'utf8')
  const frame = Buffer.allocUnsafe(5 + json.length)
  frame.writeUInt8(FILE_MAGIC, 0)
  frame.writeInt32BE(json.length, 1)
  json.copy(frame, 5)
  return frame
}

export function inferMessageType(fileName: string): MessageType {
  const ext = (fileName.split('.').pop() ?? '').toLowerCase()
  if (['jpg', 'jpeg', 'png', 'webp', 'gif'].includes(ext)) return 'IMAGE'
  if (['mp4', 'mkv', '3gp', 'webm'].includes(ext)) return 'VIDEO'
  if (['3gp', 'aac', 'm4a', 'wav', 'mp3'].includes(ext)) return 'VOICE_NOTE'
  return 'FILE'
}

export function snippetForType(type: MessageType): string {
  switch (type) {
    case 'IMAGE':
      return 'You sent a photo'
    case 'VIDEO':
      return 'You sent a video'
    case 'VOICE_NOTE':
      return 'You sent a voice note'
    default:
      return 'You sent a file'
  }
}

export function sanitizeFileName(name: string): string {
  return name.replace(/[^a-zA-Z0-9._-]/g, '_').slice(0, 180) || 'file'
}

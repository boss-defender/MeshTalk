export const TCP_PORT = 8888
export const UDP_DISCOVERY_PORT = 8887
export const PTT_PORT = 9999
export const CALL_AUDIO_PORT = 8890
export const CALL_VIDEO_PORT = 8889
export const MULTICAST_IP = '239.255.0.1'
export const DISCOVERY_INTERVAL_MS = 5000
export const PEER_UNREACHABLE_MS = 45000
export const PEER_OFFLINE_MS = 120000
export const SAMPLE_RATE = 16000
export const FILE_MAGIC = 2
export const PACKET_MAGIC = 1

export type SignalType =
  | 'CHAT_MSG'
  | 'MSG_ACK'
  | 'MSG_READ'
  | 'FILE_HEADER'
  | 'FILE_CHUNK'
  | 'CALL_INVITE'
  | 'CALL_ACCEPT'
  | 'CALL_DECLINE'
  | 'CALL_END'
  | 'SDP_OFFER'
  | 'SDP_ANSWER'
  | 'ICE_CANDIDATE'
  | 'PING'
  | 'PONG'

export interface P2PPacket {
  type: SignalType
  senderId: string
  senderName: string
  targetId: string
  payload: string
  msgId: string
  extraData?: string | null
}

export type MessageType = 'TEXT' | 'IMAGE' | 'VOICE_NOTE' | 'VIDEO' | 'FILE' | 'SYSTEM'
export type MessageStatus = 'SENDING' | 'SENT' | 'DELIVERED' | 'READ' | 'FAILED' | 'RECEIVED'

export interface Message {
  id: string
  senderId: string
  senderName: string
  chatId: string
  content: string
  type: MessageType
  filePath?: string | null
  fileSize: number
  timestamp: number
  isMine: boolean
  status: MessageStatus
  starred: boolean
}

export interface Peer {
  id: string
  displayName: string
  ipAddress: string
  port: number
  lastSeen: number
  isOnline: boolean
  rttMs: number
  unreadCount: number
  lastMessageTime: number
  lastMessageSnippet?: string | null
  avatarColor: number
}

export interface Settings {
  displayName: string
  deviceId: string
  oledTheme: boolean
  autoDeleteOldMessages: boolean
  listeningEnabled: boolean
  channel: number
}

export interface NetworkInfo {
  localIp: string
  wifiSsid: string
  tcpPort: number
  udpPort: number
  pttPort: number
  callAudioPort: number
  callVideoPort: number
  multicastGroup: string
  mdns: boolean
  multicastLock: boolean
  storagePath: string
  pttReceived: number
  pttSent: number
  callAudioReceived: number
  callAudioSent: number
}

export interface CallInfo {
  id: string
  peerIp: string
  peerName: string
  isVideo: boolean
  direction: 'incoming' | 'outgoing'
  state: 'ringing' | 'active'
  startedAt: number
}

export interface Snapshot {
  settings: Settings
  peers: Peer[]
  messages: Message[]
  network: NetworkInfo
  incomingCall: CallInfo | null
  activeCall: CallInfo | null
  connectionStatus: 'CONNECTED' | 'SEARCHING' | 'DISCONNECTED'
}

export interface FileProgress {
  id: string
  name: string
  transferId: string
  sentBytes: number
  totalBytes: number
  direction: 'send' | 'receive'
}

export type AppEvent =
  | { type: 'snapshot'; snapshot: Snapshot }
  | { type: 'toast'; message: string; tone?: 'info' | 'success' | 'error' }
  | { type: 'file-progress'; progress: FileProgress }
  | { type: 'call-video'; jpeg: Uint8Array }
  | { type: 'peer-removed'; peerId: string }

export interface SendVoiceNotePayload {
  chatId: string
  wav: ArrayBuffer
  durationMs: number
}

export interface MeshApi {
  getSnapshot(): Promise<Snapshot>
  onEvent(listener: (event: AppEvent) => void): () => void
  onPttAudio(listener: (pcm: Uint8Array) => void): () => void
  onCallAudio(listener: (pcm: Uint8Array) => void): () => void
  onCallVideo(listener: (jpeg: Uint8Array) => void): () => void

  updateDisplayName(name: string): Promise<void>
  setOledTheme(enabled: boolean): Promise<void>
  setAutoDelete(enabled: boolean): Promise<void>
  cleanUpNow(): Promise<number>
  setChannel(channel: number): Promise<void>
  setListening(enabled: boolean): Promise<void>

  sendText(chatId: string, content: string): Promise<void>
  sendFile(chatId: string): Promise<void>
  sendVoiceNote(payload: SendVoiceNotePayload): Promise<void>
  connectManual(ip: string): Promise<boolean>
  markChatRead(chatId: string): Promise<void>
  toggleStar(messageId: string, starred: boolean): Promise<void>

  startPtt(): Promise<void>
  stopPtt(): Promise<void>
  sendPttAudio(pcm: ArrayBuffer): void
  sendCallAudio(pcm: ArrayBuffer): void
  sendCallVideo(jpeg: ArrayBuffer): void
  initiateCall(peerIp: string, isVideo: boolean): Promise<void>
  acceptCall(): Promise<void>
  declineCall(): Promise<void>
  endCall(): Promise<void>

  openFile(filePath: string): Promise<void>
  showInFolder(filePath: string): Promise<void>
  openDownloadFolder(): Promise<void>
}

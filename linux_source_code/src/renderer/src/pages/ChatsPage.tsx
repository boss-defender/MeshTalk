import { useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowLeft,
  ChevronRight,
  Link2,
  MessageSquare,
  Mic,
  Paperclip,
  Phone,
  Radar,
  Send,
  Video,
  Wifi,
  WifiOff,
  X
} from 'lucide-react'
import type { Message, Peer, Snapshot } from '../../../shared/types'
import { Avatar, EmptyState, IconButton, SectionHeading } from '../components/common'
import { MessageBubble } from '../components/MessageBubble'
import { formatDuration, formatRelative, peerForChat } from '../lib/format'
import { useVoiceRecorder } from '../lib/useVoiceRecorder'

export function ChatsPage({
  snapshot,
  activeChatId,
  onSelectChat,
  onStar,
  onOpenFile,
  onShowFile,
  onToast
}: {
  snapshot: Snapshot
  activeChatId: string | null
  onSelectChat: (chatId: string | null) => void
  onStar: (message: Message) => void
  onOpenFile: (filePath: string) => void
  onShowFile: (filePath: string) => void
  onToast: (message: string, tone?: 'info' | 'success' | 'error') => void
}): React.JSX.Element {
  const [connectIp, setConnectIp] = useState('')
  const [connecting, setConnecting] = useState(false)
  const peer = peerForChat(snapshot.peers, activeChatId)
  const messages = useMemo(
    () => snapshot.messages.filter((message) => message.chatId === activeChatId),
    [snapshot.messages, activeChatId]
  )

  if (!activeChatId) {
    return (
      <ChatsList
        snapshot={snapshot}
        connectIp={connectIp}
        setConnectIp={setConnectIp}
        connecting={connecting}
        onConnect={async () => {
          const candidate = connectIp.trim()
          if (!/^\d+\.\d+\.\d+\.\d+$/.test(candidate)) {
            onToast('Enter a valid IPv4 address', 'error')
            return
          }
          setConnecting(true)
          try {
            const ok = await window.mesh.connectManual(candidate)
            if (ok) {
              setConnectIp('')
              onSelectChat(candidate)
            }
          } finally {
            setConnecting(false)
          }
        }}
        onSelectChat={onSelectChat}
      />
    )
  }

  return (
    <ChatView
      snapshot={snapshot}
      peer={peer}
      chatId={activeChatId}
      messages={messages}
      onBack={() => onSelectChat(null)}
      onStar={onStar}
      onOpenFile={onOpenFile}
      onShowFile={onShowFile}
      onToast={onToast}
    />
  )
}

function ChatsList({
  snapshot,
  connectIp,
  setConnectIp,
  connecting,
  onConnect,
  onSelectChat
}: {
  snapshot: Snapshot
  connectIp: string
  setConnectIp: (value: string) => void
  connecting: boolean
  onConnect: () => void
  onSelectChat: (chatId: string) => void
}): React.JSX.Element {
  const peers = snapshot.peers
  const online = peers.filter((peer) => peer.isOnline).length
  return (
    <div className="page page-chats">
      <SectionHeading
        eyebrow="LOCAL MESH"
        title="DISCOVERED MESH DEVICES & CHATS"
        subtitle="Tap a device for a direct peer link, or connect by IP when auto-discovery is quiet."
        right={
          <span className="count-chip">
            <Wifi size={14} /> {online} online
          </span>
        }
      />

      <form
        className="connect-card"
        onSubmit={(event) => {
          event.preventDefault()
          onConnect()
        }}
      >
        <div className="connect-copy">
          <span className="connect-icon">
            <Link2 size={18} />
          </span>
          <div>
            <strong>Connect IP</strong>
            <span>Direct link to a known device on this Wi-Fi.</span>
          </div>
        </div>
        <div className="connect-controls">
          <input
            value={connectIp}
            onChange={(event) => setConnectIp(event.target.value)}
            placeholder="192.168.1.45"
            inputMode="decimal"
            aria-label="Peer IP address"
          />
          <button type="submit" className="primary-button compact" disabled={connecting}>
            {connecting ? 'Connecting…' : 'Connect'}
          </button>
        </div>
      </form>

      {peers.length === 0 ? (
        <EmptyState
          icon={<Radar size={34} />}
          title="Searching the subnet..."
          body="Make sure the other device is running MeshTalk on the same Wi-Fi network. Discovery repeats every few seconds."
        />
      ) : (
        <div className="peer-list">
          {peers.map((peer) => (
            <PeerCard key={peer.id} peer={peer} onClick={() => onSelectChat(peer.ipAddress)} />
          ))}
        </div>
      )}

      <div className="info-strip">
        <span className="status-led" />
        <span>
          Local IP <strong>{snapshot.network.localIp}</strong> • Listening on TCP{' '}
          <strong>{snapshot.network.tcpPort}</strong> • UDP <strong>{snapshot.network.udpPort}</strong>
        </span>
      </div>
    </div>
  )
}

function PeerCard({ peer, onClick }: { peer: Peer; onClick: () => void }): React.JSX.Element {
  return (
    <button type="button" className={`peer-card${peer.isOnline ? '' : ' offline'}`} onClick={onClick}>
      <Avatar name={peer.displayName} color={peer.avatarColor} size={48} online={peer.isOnline} />
      <div className="peer-main">
        <div className="peer-title-row">
          <span className="peer-name">{peer.displayName}</span>
          {peer.unreadCount > 0 && <span className="unread-badge">{peer.unreadCount > 99 ? '99+' : peer.unreadCount}</span>}
        </div>
        <div className="peer-sub">
          <span className={`peer-link ${peer.isOnline ? 'online' : 'offline'}`}>
            {peer.isOnline ? <Wifi size={12} /> : <WifiOff size={12} />}
            {peer.ipAddress}
          </span>
          {peer.isOnline && peer.rttMs >= 0 && <span>{peer.rttMs} ms</span>}
          {!peer.isOnline && <span>Offline</span>}
        </div>
        <div className="peer-snippet">{peer.lastMessageSnippet || 'No messages yet'}</div>
      </div>
      <div className="peer-side">
        <span className="peer-time">{peer.lastMessageTime ? formatRelative(peer.lastMessageTime) : ''}</span>
        <ChevronRight size={17} />
      </div>
    </button>
  )
}

function ChatView({
  snapshot,
  peer,
  chatId,
  messages,
  onBack,
  onStar,
  onOpenFile,
  onShowFile,
  onToast
}: {
  snapshot: Snapshot
  peer?: Peer
  chatId: string
  messages: Message[]
  onBack: () => void
  onStar: (message: Message) => void
  onOpenFile: (filePath: string) => void
  onShowFile: (filePath: string) => void
  onToast: (message: string, tone?: 'info' | 'success' | 'error') => void
}): React.JSX.Element {
  const [draft, setDraft] = useState('')
  const scrollRef = useRef<HTMLDivElement | null>(null)
  const inputRef = useRef<HTMLTextAreaElement | null>(null)

  const recorder = useVoiceRecorder({
    onComplete: async (wav, durationMs) => {
      await window.mesh.sendVoiceNote({ chatId, wav, durationMs })
    },
    onError: (message) => onToast(message, 'error')
  })

  useEffect(() => {
    const unread = peer?.unreadCount ?? 0
    if (unread > 0) void window.mesh.markChatRead(chatId)
  }, [chatId, peer?.unreadCount])

  useEffect(() => {
    const element = scrollRef.current
    if (element) element.scrollTop = element.scrollHeight
  }, [messages.length, chatId])

  useEffect(() => {
    inputRef.current?.focus()
  }, [chatId])

  const send = async (): Promise<void> => {
    const content = draft.trim()
    if (!content) return
    setDraft('')
    await window.mesh.sendText(chatId, content)
  }

  const call = (video: boolean): void => {
    const target = peer?.ipAddress ?? chatId
    if (!peer?.isOnline) onToast('Peer is offline — trying direct link anyway', 'info')
    void window.mesh.initiateCall(target, video)
  }

  const showRecorder = recorder.recording || recorder.preparing

  return (
    <div className="page page-conversation">
      <div className="chat-header">
        <IconButton label="Back to devices" onClick={onBack} className="back-button">
          <ArrowLeft size={19} />
        </IconButton>
        <Avatar name={peer?.displayName ?? chatId} color={peer?.avatarColor} size={42} online={peer?.isOnline} />
        <div className="chat-header-copy">
          <strong>{peer?.displayName ?? chatId}</strong>
          <span>
            {peer?.isOnline ? <Wifi size={12} /> : <WifiOff size={12} />} {peer?.ipAddress ?? chatId}
            {peer?.isOnline && peer.rttMs >= 0 ? ` • ${peer.rttMs} ms` : ''}
          </span>
        </div>
        <div className="chat-header-actions">
          <IconButton label="Voice Call" onClick={() => call(false)}>
            <Phone size={18} />
          </IconButton>
          <IconButton label="Video Call" onClick={() => call(true)}>
            <Video size={19} />
          </IconButton>
        </div>
      </div>

      <div className="message-scroll" ref={scrollRef}>
        {messages.length === 0 ? (
          <EmptyState
            icon={<MessageSquare size={32} />}
            title="No messages yet"
            body="Say hello over the local mesh. Text, photos, files and voice notes all travel directly between devices."
          />
        ) : (
          <div className="message-list">
            {messages.map((message, index) => {
              const previous = messages[index - 1]
              const newDay =
                !previous || new Date(previous.timestamp).toDateString() !== new Date(message.timestamp).toDateString()
              return (
                <div key={message.id}>
                  {newDay && (
                    <div className="day-divider">
                      <span>
                        {new Date(message.timestamp).toLocaleDateString([], {
                          weekday: 'short',
                          month: 'short',
                          day: 'numeric'
                        })}
                      </span>
                    </div>
                  )}
                  <MessageBubble
                    message={message}
                    showSender={false}
                    onStar={onStar}
                    onOpenFile={onOpenFile}
                    onShowFile={onShowFile}
                    onToast={onToast}
                  />
                </div>
              )
            })}
            <div className="file-location">
              📁 Location: {snapshot.network.storagePath}
            </div>
          </div>
        )}
      </div>

      {showRecorder ? (
        <div className="composer recorder-composer">
          <span className="recording-dot" />
          <div className="recording-copy">
            <strong>Recording Voice Note...</strong>
            <span>{formatDuration(recorder.elapsedMs)} • Release to send</span>
          </div>
          <button type="button" className="danger-ghost" onClick={recorder.cancel}>
            <X size={16} /> Cancel
          </button>
          <button type="button" className="primary-button" onClick={() => void recorder.stopAndSend()}>
            <Send size={15} /> Send Voice Note
          </button>
        </div>
      ) : (
        <div className="composer">
          <IconButton label="Attach File" onClick={() => void window.mesh.sendFile(chatId)}>
            <Paperclip size={19} />
          </IconButton>
          <textarea
            ref={inputRef}
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault()
                void send()
              }
            }}
            rows={1}
            placeholder="Type message over Wi-Fi..."
            aria-label="Message"
          />
          <IconButton label="Voice Note" onClick={() => void recorder.start()}>
            <Mic size={19} />
          </IconButton>
          <button type="button" className="send-button" onClick={() => void send()} disabled={!draft.trim()}>
            <Send size={18} />
            <span>Send</span>
          </button>
        </div>
      )}
    </div>
  )
}

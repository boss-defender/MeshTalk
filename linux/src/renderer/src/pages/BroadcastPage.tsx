import { useEffect, useMemo, useRef, useState } from 'react'
import { Mic, Paperclip, RadioTower, Send, Users, X } from 'lucide-react'
import type { Message, Snapshot } from '../../../shared/types'
import { EmptyState, IconButton, SectionHeading, Waveform } from '../components/common'
import { MessageBubble } from '../components/MessageBubble'
import { formatDuration } from '../lib/format'
import { useVoiceRecorder } from '../lib/useVoiceRecorder'

export function BroadcastPage({
  snapshot,
  onStar,
  onOpenFile,
  onShowFile,
  onToast
}: {
  snapshot: Snapshot
  onStar: (message: Message) => void
  onOpenFile: (filePath: string) => void
  onShowFile: (filePath: string) => void
  onToast: (message: string, tone?: 'info' | 'success' | 'error') => void
}): React.JSX.Element {
  const [draft, setDraft] = useState('')
  const scrollRef = useRef<HTMLDivElement | null>(null)
  const messages = useMemo(
    () => snapshot.messages.filter((message) => message.chatId === 'GLOBAL'),
    [snapshot.messages]
  )
  const recorder = useVoiceRecorder({
    onComplete: async (wav, durationMs) => {
      await window.mesh.sendVoiceNote({ chatId: 'GLOBAL', wav, durationMs })
    },
    onError: (message) => onToast(message, 'error')
  })

  useEffect(() => {
    const element = scrollRef.current
    if (element) element.scrollTop = element.scrollHeight
  }, [messages.length])

  const send = async (): Promise<void> => {
    const content = draft.trim()
    if (!content) return
    setDraft('')
    await window.mesh.sendText('GLOBAL', content)
  }

  const online = snapshot.peers.filter((peer) => peer.isOnline).length
  const showRecorder = recorder.recording || recorder.preparing
  const idleLevels = [0.18, 0.42, 0.27, 0.64, 0.36, 0.5, 0.22, 0.31, 0.18, 0.46, 0.61, 0.34, 0.52, 0.24, 0.4, 0.16]

  return (
    <div className="page page-broadcast">
      <SectionHeading
        eyebrow="ONE TO MANY"
        title="NETWORK BROADCAST HUB"
        subtitle="Posts, audio notes & files reach ALL connected devices on local Wi-Fi"
        right={
          <span className="count-chip">
            <Users size={14} /> {online} receiving
          </span>
        }
      />

      <div className="broadcast-banner">
        <span className="broadcast-icon">
          <RadioTower size={19} />
        </span>
        <div>
          <strong>Subnet-wide channel</strong>
          <span>No server, no internet. Every online MeshTalk device receives this feed.</span>
        </div>
        <Waveform levels={idleLevels} tone="amber" height={28} />
      </div>

      <section className="feed-card">
        <header className="feed-header">
          <div>
            <h3>Broadcast Feed</h3>
            <span>{messages.length} {messages.length === 1 ? 'message' : 'messages'} on this LAN</span>
          </div>
          <span className="live-chip">
            <span className="status-led" /> LIVE
          </span>
        </header>

        <div className="feed-scroll" ref={scrollRef}>
          {messages.length === 0 ? (
            <EmptyState
              icon={<RadioTower size={34} />}
              title="No broadcast messages yet"
              body="Be the first to post to the subnet! Text, voice notes and files will appear instantly on every connected device."
            />
          ) : (
            <div className="message-list broadcast-list">
              {messages.map((message) => (
                <MessageBubble
                  key={message.id}
                  message={message}
                  showSender
                  onStar={onStar}
                  onOpenFile={onOpenFile}
                  onShowFile={onShowFile}
                  onToast={onToast}
                />
              ))}
            </div>
          )}
        </div>

        {showRecorder ? (
          <div className="composer recorder-composer">
            <span className="recording-dot" />
            <div className="recording-copy">
              <strong>Recording Broadcast Voice Note...</strong>
              <span>{formatDuration(recorder.elapsedMs)} • Sent to every device</span>
            </div>
            <button type="button" className="danger-ghost" onClick={recorder.cancel}>
              <X size={16} /> Cancel
            </button>
            <button type="button" className="primary-button" onClick={() => void recorder.stopAndSend()}>
              <Send size={15} /> Send Broadcast Voice Note
            </button>
          </div>
        ) : (
          <div className="composer broadcast-composer">
            <IconButton label="Attach File to Broadcast" onClick={() => void window.mesh.sendFile('GLOBAL')}>
              <Paperclip size={19} />
            </IconButton>
            <textarea
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && !event.shiftKey) {
                  event.preventDefault()
                  void send()
                }
              }}
              rows={1}
              placeholder="Broadcast to all devices..."
              aria-label="Broadcast message"
            />
            <IconButton label="Voice Broadcast" onClick={() => void recorder.start()}>
              <Mic size={19} />
            </IconButton>
            <button type="button" className="send-button" onClick={() => void send()} disabled={!draft.trim()}>
              <Send size={18} />
              <span>Send</span>
            </button>
          </div>
        )}
      </section>

      <p className="broadcast-footnote">
        Voice Broadcast records a WAV voice note and transfers it to every online peer over the encrypted file channel.
      </p>
    </div>
  )
}

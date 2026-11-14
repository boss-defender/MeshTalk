import { Check, CheckCheck, Clock, FileText, FolderOpen, Music, Star } from 'lucide-react'
import type { Message } from '../../../shared/types'
import { downloadStatusLabel, formatBytes, formatClock, mediaUrl } from '../lib/format'

export function MessageBubble({
  message,
  showSender,
  onStar,
  onOpenFile,
  onShowFile,
  onToast
}: {
  message: Message
  showSender: boolean
  onStar: (message: Message) => void
  onOpenFile: (filePath: string) => void
  onShowFile: (filePath: string) => void
  onToast: (message: string) => void
}): React.JSX.Element {
  const mine = message.isMine
  const source = mediaUrl(message.filePath)
  const hasFile = Boolean(message.filePath)

  const openFile = (): void => {
    if (message.filePath) onOpenFile(message.filePath)
    else onToast('File is no longer available')
  }

  const renderContent = (): React.JSX.Element => {
    if (message.type === 'IMAGE' && source) {
      return (
        <button type="button" className="image-bubble" onClick={openFile} title="Open image">
          <img src={source} alt={message.content || 'Shared image'} loading="lazy" />
        </button>
      )
    }
    if (message.type === 'VIDEO' && source) {
      return (
        <video className="video-bubble" controls preload="metadata" src={source}>
          Your browser does not support video playback.
        </video>
      )
    }
    if (message.type === 'VOICE_NOTE') {
      return (
        <div className="voice-bubble">
          <span className="voice-icon">
            <Music size={17} />
          </span>
          <div className="voice-meta">
            <span className="voice-label">Voice Note</span>
            {source ? (
              <audio controls preload="metadata" src={source} />
            ) : (
              <span className="voice-missing">{message.content}</span>
            )}
          </div>
        </div>
      )
    }
    if (message.type === 'FILE') {
      return (
        <div className="file-bubble">
          <div className="file-icon">
            <FileText size={21} />
          </div>
          <div className="file-copy">
            <span className="file-name" title={message.content}>
              {message.content}
            </span>
            <span className="file-size">
              {formatBytes(message.fileSize)} {hasFile ? '• Saved locally' : '• Unavailable'}
            </span>
            {hasFile && (
              <div className="file-actions">
                <button type="button" onClick={openFile}>
                  Open
                </button>
                <button type="button" onClick={() => message.filePath && onShowFile(message.filePath)}>
                  <FolderOpen size={13} /> Folder
                </button>
              </div>
            )}
          </div>
        </div>
      )
    }
    return <p className="text-bubble">{message.content}</p>
  }

  return (
    <div className={`message-row ${mine ? 'mine' : 'theirs'}`}>
      <div className={`message-bubble type-${message.type.toLowerCase()}`}>
        {showSender && !mine && <div className="message-sender">{message.senderName}</div>}
        {renderContent()}
        <div className="message-footer">
          <span className="message-time">{formatClock(message.timestamp)}</span>
          {mine && (
            <span className={`message-status status-${message.status.toLowerCase()}`}>
              {message.status === 'SENDING' ? (
                <Clock size={12} />
              ) : message.status === 'DELIVERED' || message.status === 'READ' ? (
                <CheckCheck size={13} />
              ) : (
                <Check size={12} />
              )}
              {downloadStatusLabel(message.status)}
            </span>
          )}
          {!mine && message.type === 'FILE' && (
            <span className="message-status muted">Received</span>
          )}
          <button
            type="button"
            className={`star-button${message.starred ? ' starred' : ''}`}
            aria-label={message.starred ? 'Remove star' : 'Star message'}
            title={message.starred ? 'Remove star' : 'Star Message'}
            onClick={() => onStar(message)}
          >
            <Star size={13} fill={message.starred ? 'currentColor' : 'none'} />
          </button>
        </div>
      </div>
    </div>
  )
}

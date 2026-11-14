import { AlertCircle, CheckCircle2, Info, X } from 'lucide-react'
import type { FileProgress } from '../../../shared/types'
import { formatBytes } from '../lib/format'

export interface ToastItem {
  id: number
  message: string
  tone: 'info' | 'success' | 'error'
}

export function Toasts({
  toasts,
  onDismiss
}: {
  toasts: ToastItem[]
  onDismiss: (id: number) => void
}): React.JSX.Element {
  return (
    <div className="toast-stack" role="status" aria-live="polite">
      {toasts.map((toast) => (
        <div key={toast.id} className={`toast ${toast.tone}`}>
          <span className="toast-icon">
            {toast.tone === 'success' ? (
              <CheckCircle2 size={17} />
            ) : toast.tone === 'error' ? (
              <AlertCircle size={17} />
            ) : (
              <Info size={17} />
            )}
          </span>
          <span>{toast.message}</span>
          <button type="button" aria-label="Dismiss" onClick={() => onDismiss(toast.id)}>
            <X size={14} />
          </button>
        </div>
      ))}
    </div>
  )
}

export function TransferTray({ transfers }: { transfers: FileProgress[] }): React.JSX.Element | null {
  if (transfers.length === 0) return null
  return (
    <div className="transfer-tray">
      {transfers.map((transfer) => {
        const percent =
          transfer.totalBytes > 0
            ? Math.min(100, Math.round((transfer.sentBytes / transfer.totalBytes) * 100))
            : 0
        const done = transfer.totalBytes > 0 && transfer.sentBytes >= transfer.totalBytes
        return (
          <div key={`${transfer.transferId}-${transfer.direction}`} className="transfer-item">
            <div className="transfer-head">
              <span className="transfer-name">
                {transfer.direction === 'send' ? 'Uploading' : 'Receiving'} {transfer.name}
              </span>
              <span className="transfer-meta">
                {done ? 'Complete' : `${percent}% • ${formatBytes(transfer.sentBytes)} / ${formatBytes(transfer.totalBytes)}`}
              </span>
            </div>
            <div className="progress-track">
              <div className="progress-fill" style={{ width: `${done ? 100 : percent}%` }} />
            </div>
          </div>
        )
      })}
    </div>
  )
}

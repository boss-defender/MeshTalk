import { Moon, Pencil, Radio, Settings, Sun } from 'lucide-react'
import type { Snapshot } from '../../../shared/types'
import { IconButton } from './common'

export function TopBar({
  snapshot,
  onEdit,
  onToggleTheme,
  onOpenSettings
}: {
  snapshot: Snapshot
  onEdit: () => void
  onToggleTheme: () => void
  onOpenSettings: () => void
}): React.JSX.Element {
  const online = snapshot.peers.filter((peer) => peer.isOnline).length
  const statusText =
    snapshot.connectionStatus === 'CONNECTED'
      ? `Connected (${online})`
      : snapshot.connectionStatus === 'SEARCHING'
        ? 'Searching...'
        : 'Disconnected'
  return (
    <header className="topbar">
      <div className="brand">
        <div className="brand-mark">
          <Radio size={22} strokeWidth={2.4} />
        </div>
        <div className="brand-copy">
          <div className="brand-title">MeshTalk</div>
          <div className="brand-sub">P2P MESH LINK</div>
        </div>
      </div>

      <div
        className={`connection-badge ${
          snapshot.connectionStatus === 'CONNECTED'
            ? 'connected'
            : snapshot.connectionStatus === 'SEARCHING'
              ? 'searching'
              : 'disconnected'
        }`}
        title={`Local IP ${snapshot.network.localIp} • ${snapshot.network.wifiSsid}`}
      >
        <span className="connection-dot" />
        {statusText}
      </div>

      <div className="topbar-actions">
        <button type="button" className="identity-chip" onClick={onEdit} title="Edit device name">
          <span className="identity-label">Name:</span>
          <span className="identity-value">{snapshot.settings.displayName}</span>
          <Pencil size={13} />
        </button>
        <IconButton label="Toggle Dark/OLED Theme" onClick={onToggleTheme} active={snapshot.settings.oledTheme}>
          {snapshot.settings.oledTheme ? <Sun size={18} /> : <Moon size={18} />}
        </IconButton>
        <IconButton label="Settings & Diagnostics" onClick={onOpenSettings}>
          <Settings size={19} />
        </IconButton>
      </div>
    </header>
  )
}

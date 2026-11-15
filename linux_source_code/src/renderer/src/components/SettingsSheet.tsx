import { useEffect, useMemo, useState } from 'react'
import {
  Cpu,
  FolderOpen,
  HardDrive,
  Info,
  Moon,
  Network,
  RadioTower,
  Save,
  Trash2,
  UserRound,
  Wifi,
  X
} from 'lucide-react'
import type { Snapshot } from '../../../shared/types'
import { Toggle } from './common'

export function SettingsSheet({
  snapshot,
  onClose,
  onToast
}: {
  snapshot: Snapshot
  onClose: () => void
  onToast: (message: string, tone?: 'info' | 'success' | 'error') => void
}): React.JSX.Element {
  const [name, setName] = useState(snapshot.settings.displayName)
  const [confirmCleanup, setConfirmCleanup] = useState(false)
  const [cleaning, setCleaning] = useState(false)

  useEffect(() => {
    setName(snapshot.settings.displayName)
  }, [snapshot.settings.displayName])

  const online = snapshot.peers.filter((peer) => peer.isOnline).length
  const statusLabel =
    snapshot.connectionStatus === 'CONNECTED'
      ? `Connected (${online})`
      : snapshot.connectionStatus === 'SEARCHING'
        ? 'Searching...'
        : 'Disconnected'
  const messageCount = snapshot.messages.length
  const starredCount = useMemo(() => snapshot.messages.filter((m) => m.starred).length, [snapshot.messages])

  const saveName = async (): Promise<void> => {
    const trimmed = name.trim()
    if (!trimmed || trimmed === snapshot.settings.displayName) return
    await window.mesh.updateDisplayName(trimmed)
  }

  const cleanUp = async (): Promise<void> => {
    setCleaning(true)
    try {
      const removed = await window.mesh.cleanUpNow()
      setConfirmCleanup(false)
      onToast(removed > 0 ? `Removed ${removed} old message${removed === 1 ? '' : 's'}` : 'Nothing to clean up', 'success')
    } catch {
      onToast('Could not clean up storage', 'error')
    } finally {
      setCleaning(false)
    }
  }

  return (
    <div className="sheet-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget) onClose()
    }}>
      <aside className="settings-sheet" role="dialog" aria-modal="true" aria-label="Settings and Diagnostics">
        <header className="sheet-header">
          <div>
            <span className="eyebrow">MESHTALK CONTROL</span>
            <h2>Settings &amp; Diagnostics</h2>
          </div>
          <button type="button" className="sheet-close" aria-label="Close settings" onClick={onClose}>
            <X size={19} />
          </button>
        </header>

        <div className="sheet-body">
          <section className="settings-section">
            <div className="settings-section-title">
              <UserRound size={16} />
              <span>Device Profile</span>
            </div>
            <div className="settings-card">
              <label className="field-label" htmlFor="device-name">
                YOUR DEVICE NICKNAME
              </label>
              <p className="field-help">This name will be broadcast to all other devices on the Wi-Fi network:</p>
              <div className="field-row">
                <input
                  id="device-name"
                  value={name}
                  maxLength={40}
                  onChange={(event) => setName(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter') void saveName()
                  }}
                  placeholder="Walkie-User-1234"
                />
                <button
                  type="button"
                  className="primary-button"
                  disabled={!name.trim() || name.trim() === snapshot.settings.displayName}
                  onClick={() => void saveName()}
                >
                  <Save size={15} /> Save Name
                </button>
              </div>
              <div className="device-id">
                <Cpu size={13} /> Device ID <code>{snapshot.settings.deviceId}</code>
              </div>
            </div>
          </section>

          <section className="settings-section">
            <div className="settings-section-title">
              <Moon size={16} />
              <span>Appearance</span>
            </div>
            <div className="settings-card row-card">
              <div>
                <strong>OLED Black Theme</strong>
                <span>Deep black surfaces for OLED displays.</span>
              </div>
              <Toggle
                checked={snapshot.settings.oledTheme}
                label="OLED Black Theme"
                onChange={(enabled) => void window.mesh.setOledTheme(enabled)}
              />
            </div>
          </section>

          <section className="settings-section">
            <div className="settings-section-title">
              <HardDrive size={16} />
              <span>Storage Management</span>
            </div>
            <div className="settings-card">
              <div className="row-card">
                <div>
                  <strong>Auto-Delete Non-Starred Messages</strong>
                  <span>Remove chats older than 2 days automatically. Downloaded files are preserved.</span>
                </div>
                <Toggle
                  checked={snapshot.settings.autoDeleteOldMessages}
                  label="Auto-delete non-starred messages"
                  onChange={(enabled) => void window.mesh.setAutoDelete(enabled)}
                />
              </div>
              <div className="storage-stats">
                <span>{messageCount} messages stored</span>
                <span>{starredCount} starred</span>
                <button type="button" className="link-button" onClick={() => void window.mesh.openDownloadFolder()}>
                  <FolderOpen size={13} /> Open folder
                </button>
              </div>
              <button type="button" className="danger-button" onClick={() => setConfirmCleanup(true)}>
                <Trash2 size={15} /> Clear Storage
              </button>
            </div>
          </section>

          <section className="settings-section">
            <div className="settings-section-title">
              <Network size={16} />
              <span>Network Status</span>
            </div>
            <div className="settings-card diagnostics">
              <DiagRow label="P2P Mesh Status" value={statusLabel} accent={snapshot.connectionStatus === 'CONNECTED'} />
              <DiagRow label="Connected Wi-Fi" value={snapshot.network.wifiSsid} icon={<Wifi size={13} />} />
              <DiagRow label="Local IP Address" value={snapshot.network.localIp} />
              <DiagRow label="TCP Messenger Port" value={String(snapshot.network.tcpPort)} />
              <DiagRow label="UDP Discovery Port" value={String(snapshot.network.udpPort)} />
              <DiagRow label="UDP Walkie-Talkie Port" value={String(snapshot.network.pttPort)} />
              <DiagRow label="Call Audio Port" value={String(snapshot.network.callAudioPort)} />
              <DiagRow label="Call Video Port" value={String(snapshot.network.callVideoPort)} />
              <DiagRow
                label="mDNS NSD Discovery"
                value={snapshot.network.mdns ? 'Active (_walkietalkie._tcp.)' : 'Unavailable — UDP discovery active'}
                icon={<RadioTower size={13} />}
              />
              <DiagRow
                label="MulticastLock State"
                value={snapshot.network.multicastLock ? 'Acquired (High Performance)' : 'Released'}
              />
              <div className="diag-row path-row">
                <span>Default Storage Path</span>
                <div>
                  <code>{snapshot.network.storagePath}</code>
                  <button type="button" className="link-button" onClick={() => void window.mesh.openDownloadFolder()}>
                    <FolderOpen size={13} /> Open
                  </button>
                </div>
              </div>
            </div>
          </section>

          <section className="settings-section">
            <div className="settings-section-title">
              <Info size={16} />
              <span>P2P Guide</span>
            </div>
            <div className="settings-card guide-card">
              <h4>OFFLINE TESTING GUIDE</h4>
              <ol>
                <li>Connect every phone or computer to the same Wi-Fi network. Internet is not required.</li>
                <li>Open MeshTalk on each device and allow microphone (and camera for video calls) access.</li>
                <li>Hold the walkie dial to speak. Open Chats &amp; Calls for text, files and direct calls.</li>
              </ol>
              <div className="protocol-tags">
                <span>TCP {snapshot.network.tcpPort}</span>
                <span>UDP {snapshot.network.udpPort}</span>
                <span>PTT {snapshot.network.pttPort}</span>
                <span>{snapshot.network.multicastGroup}</span>
              </div>
            </div>
          </section>

          <footer className="sheet-footer">
            MeshTalk for Linux 1.0.0 • LAN-only, account-free, protocol-compatible with the Android app.
          </footer>
        </div>

        {confirmCleanup && (
          <div className="confirm-backdrop" role="presentation">
            <div className="confirm-card" role="alertdialog" aria-modal="true" aria-label="Clear old storage">
              <h3>Clear Old Storage &amp; Chat History?</h3>
              <p>
                This removes non-starred messages older than 2 days. Files saved under{' '}
                <code>{snapshot.network.storagePath}</code> are preserved.
              </p>
              <div className="confirm-actions">
                <button type="button" className="ghost-button" onClick={() => setConfirmCleanup(false)} disabled={cleaning}>
                  Cancel
                </button>
                <button type="button" className="danger-button" onClick={() => void cleanUp()} disabled={cleaning}>
                  {cleaning ? 'Cleaning…' : 'Clean Up'}
                </button>
              </div>
            </div>
          </div>
        )}
      </aside>
    </div>
  )
}

function DiagRow({
  label,
  value,
  accent = false,
  icon
}: {
  label: string
  value: string
  accent?: boolean
  icon?: React.ReactNode
}): React.JSX.Element {
  return (
    <div className="diag-row">
      <span>{label}</span>
      <strong className={accent ? 'accent' : ''}>
        {icon}
        {value}
      </strong>
    </div>
  )
}

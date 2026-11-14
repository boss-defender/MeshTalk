import { useEffect, useRef, useState } from 'react'
import { ChevronLeft, ChevronRight, Headphones, Radio, Signal, Volume2, VolumeX, Waves } from 'lucide-react'
import type { Snapshot } from '../../../shared/types'
import { Avatar, SectionHeading, Toggle, Waveform } from '../components/common'

export function WalkiePage({
  snapshot,
  pttActive,
  pttReceiving,
  levels,
  callActive,
  onStartPtt,
  onStopPtt,
  onToast
}: {
  snapshot: Snapshot
  pttActive: boolean
  pttReceiving: boolean
  levels: number[]
  callActive: boolean
  onStartPtt: () => Promise<void>
  onStopPtt: () => void
  onToast: (message: string, tone?: 'info' | 'success' | 'error') => void
}): React.JSX.Element {
  const [pressed, setPressed] = useState(false)
  const pointerId = useRef<number | null>(null)
  const pressedRef = useRef(false)
  const stopRef = useRef(onStopPtt)
  stopRef.current = onStopPtt
  pressedRef.current = pressed
  const listening = snapshot.settings.listeningEnabled
  const onlinePeers = snapshot.peers.filter((peer) => peer.isOnline)
  const channel = snapshot.settings.channel

  const start = (): void => {
    if (pressed || callActive) return
    setPressed(true)
    void onStartPtt()
  }

  const stop = (): void => {
    if (!pressed) return
    setPressed(false)
    onStopPtt()
  }

  useEffect(() => {
    return () => {
      if (pressedRef.current) stopRef.current()
    }
  }, [])

  const status = callActive
    ? 'CALL ACTIVE'
    : pttActive || pressed
      ? 'TRANSMITTING'
      : pttReceiving
        ? 'RECEIVING'
        : !listening
          ? 'MUTED'
          : 'IDLE'

  const tone = pttActive || pressed ? 'amber' : pttReceiving ? 'green' : 'cyan'

  const setChannel = (next: number): void => {
    void window.mesh.setChannel(Math.max(1, Math.min(99, next)))
  }

  return (
    <div className="page page-walkie">
      <SectionHeading
        eyebrow="TACTICAL PUSH-TO-TALK"
        title="WALKIE-TALKIE"
        subtitle="Direct device-to-device voice over local Wi-Fi. Hold the dial, speak, release to listen."
        right={
          <span className={`ptt-status-pill ${status.toLowerCase().replace(' ', '-')}`}>
            <span className="status-led" />
            {status}
          </span>
        }
      />

      <div className="channel-card">
        <span className="channel-label">FREQUENCY CHANNEL</span>
        <div className="channel-row">
          <button
            type="button"
            className="channel-step"
            aria-label="Previous channel"
            onClick={() => setChannel(channel - 1)}
          >
            <ChevronLeft size={20} />
          </button>
          <div className="channel-value">
            <strong>CH-{channel.toString().padStart(2, '0')}</strong>
            <span>LOCAL SUBNET</span>
          </div>
          <button
            type="button"
            className="channel-step"
            aria-label="Next channel"
            onClick={() => setChannel(channel + 1)}
          >
            <ChevronRight size={20} />
          </button>
        </div>
        <div className="channel-meta">
          <span>
            <Signal size={13} /> {onlinePeers.length} {onlinePeers.length === 1 ? 'peer' : 'peers'} in range
          </span>
          <span>
            <Waves size={13} /> UDP {snapshot.network.pttPort} • 16 kHz PCM
          </span>
        </div>
      </div>

      <div className={`ptt-stage ${pttActive || pressed ? 'transmitting' : pttReceiving ? 'receiving' : 'idle'}`}>
        <div className="ptt-rings" aria-hidden="true">
          <span />
          <span />
          <span />
        </div>
        <button
          type="button"
          className={`ptt-dial${pttActive || pressed ? ' active' : ''}${callActive ? ' disabled' : ''}`}
          aria-label="Hold to speak"
          aria-pressed={pttActive || pressed}
          onPointerDown={(event) => {
            if (callActive) {
              onToast('End the active call before using the walkie-talkie', 'error')
              return
            }
            pointerId.current = event.pointerId
            event.currentTarget.setPointerCapture(event.pointerId)
            start()
          }}
          onPointerUp={() => {
            if (pointerId.current !== null) pointerId.current = null
            stop()
          }}
          onPointerCancel={stop}
          onLostPointerCapture={stop}
          onKeyDown={(event) => {
            if (event.key === ' ' || event.key === 'Enter') {
              event.preventDefault()
              if (!event.repeat) start()
            }
          }}
          onKeyUp={(event) => {
            if (event.key === ' ' || event.key === 'Enter') {
              event.preventDefault()
              stop()
            }
          }}
        >
          <span className="ptt-inner">
            <Radio size={44} strokeWidth={1.8} />
            <strong>{pttActive || pressed ? 'ON AIR' : callActive ? 'CALL ACTIVE' : 'HOLD TO SPEAK'}</strong>
            <span>{pttActive || pressed ? 'Release to stop' : 'Push to talk'}</span>
          </span>
        </button>
        <p className="ptt-hint">
          {callActive
            ? 'Walkie-talkie is paused while a voice or video call is active.'
            : pttReceiving
              ? 'Incoming audio from the channel…'
              : 'Press and hold to talk over local Wi-Fi'}
        </p>
      </div>

      <div className="waveform-card">
        <div className="waveform-head">
          <span>LIVE AUDIO WAVEFORM</span>
          <span className="waveform-state">{pttActive || pressed ? 'TX' : pttReceiving ? 'RX' : 'STANDBY'}</span>
        </div>
        <Waveform levels={levels} tone={tone} active={pttActive || pressed || pttReceiving} height={64} />
        <div className="waveform-scale">
          <span>0 ms</span>
          <span>20 ms</span>
          <span>40 ms</span>
          <span>60 ms</span>
        </div>
      </div>

      <div className="walkie-grid">
        <div className="walkie-card">
          <div className="walkie-card-head">
            <span className="walkie-card-icon">
              <Volume2 size={18} />
            </span>
            <div>
              <strong>Listen to channel</strong>
              <span>Play incoming walkie audio through the speaker</span>
            </div>
            <Toggle
              checked={listening}
              label="Listen to walkie channel"
              onChange={(enabled) => void window.mesh.setListening(enabled)}
            />
          </div>
          <div className="walkie-card-foot">
            {listening ? (
              <>
                <Volume2 size={14} /> Listening on UDP {snapshot.network.pttPort}
              </>
            ) : (
              <>
                <VolumeX size={14} /> Incoming channel audio is muted
              </>
            )}
          </div>
        </div>

        <div className="walkie-card">
          <div className="walkie-card-head">
            <span className="walkie-card-icon amber">
              <Headphones size={18} />
            </span>
            <div>
              <strong>Peers in range</strong>
              <span>
                {onlinePeers.length === 0
                  ? 'Searching for other MeshTalk devices'
                  : `Your voice reaches ${onlinePeers.length} nearby ${onlinePeers.length === 1 ? 'device' : 'devices'}`}
              </span>
            </div>
          </div>
          <div className="walkie-peers">
            {onlinePeers.length === 0 ? (
              <span className="walkie-empty">No peers discovered yet</span>
            ) : (
              onlinePeers.slice(0, 6).map((peer) => (
                <span key={peer.id} className="walkie-peer" title={`${peer.displayName} • ${peer.ipAddress}`}>
                  <Avatar name={peer.displayName} color={peer.avatarColor} size={28} online />
                  {peer.displayName.split(' ')[0]}
                </span>
              ))
            )}
          </div>
        </div>
      </div>

      <div className="walkie-notes">
        <p>• Audio is sent directly to nearby devices as raw PCM over UDP. No server or internet hop.</p>
        <p>• Release the dial to listen. The channel preset is local to this device’s controls.</p>
        <p>• Multicast group {snapshot.network.multicastGroup} • {snapshot.network.multicastLock ? 'MulticastLock acquired' : 'MulticastLock released'}.</p>
      </div>
    </div>
  )
}

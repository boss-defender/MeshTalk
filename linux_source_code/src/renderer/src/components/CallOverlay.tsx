import { useEffect, useRef, useState } from 'react'
import {
  Camera,
  Mic,
  MicOff,
  Phone,
  PhoneOff,
  RefreshCw,
  ShieldCheck,
  Video,
  VideoOff,
  Volume2,
  VolumeX
} from 'lucide-react'
import type { CallInfo } from '../../../shared/types'
import { startCapture, startVideoSender } from '../audio'
import { Avatar } from './common'
import { formatDuration } from '../lib/format'

export function CallOverlay({
  activeCall,
  incomingCall,
  remoteFrameUrl,
  onAccept,
  onDecline,
  onEnd,
  onToast
}: {
  activeCall: CallInfo | null
  incomingCall: CallInfo | null
  remoteFrameUrl: string | null
  onAccept: () => void
  onDecline: () => void
  onEnd: () => void
  onToast: (message: string, tone?: 'info' | 'success' | 'error') => void
}): React.JSX.Element | null {
  const call = activeCall ?? incomingCall
  const incoming = Boolean(incomingCall)
  const [muted, setMuted] = useState(false)
  const [speaker, setSpeaker] = useState(true)
  const [videoOn, setVideoOn] = useState(Boolean(call?.isVideo))
  const [facing, setFacing] = useState<'user' | 'environment'>('user')
  const [localStream, setLocalStream] = useState<MediaStream | null>(null)
  const [micError, setMicError] = useState<string | null>(null)
  const [now, setNow] = useState(Date.now())
  const localVideoRef = useRef<HTMLVideoElement | null>(null)
  const mutedRef = useRef(false)
  const toastRef = useRef(onToast)
  toastRef.current = onToast

  const isActive = call?.state === 'active'
  const isRingingOutgoing = !incoming && call?.state === 'ringing'
  const showVideo = Boolean(call?.isVideo || remoteFrameUrl || (videoOn && localStream))

  useEffect(() => {
    setMuted(false)
    setSpeaker(true)
    setVideoOn(Boolean(activeCall?.isVideo))
    setFacing('user')
    setMicError(null)
  }, [call?.id])

  useEffect(() => {
    mutedRef.current = muted
  }, [muted])

  useEffect(() => {
    if (!isActive) return
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [isActive])

  // Continuous microphone stream for the call; mute simply drops packets.
  useEffect(() => {
    if (!call || call.state !== 'active') return
    let cancelled = false
    let capture: { stop: () => void } | null = null
    startCapture((pcm) => {
      if (!mutedRef.current) window.mesh.sendCallAudio(pcm)
    })
      .then((handle) => {
        if (cancelled) handle.stop()
        else capture = handle
      })
      .catch((err) => {
        setMicError(err instanceof Error ? err.message : String(err))
        toastRef.current('Microphone unavailable — the call is receive-only', 'error')
      })
    return () => {
      cancelled = true
      capture?.stop()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [call?.id, call?.state])

  // Camera stream, compressed to JPEG and sent over UDP 8889 (Android-compatible).
  useEffect(() => {
    if (!call || call.state !== 'active' || !videoOn) {
      setLocalStream(null)
      return
    }
    let cancelled = false
    let handle: { stop: () => void; stream: MediaStream } | null = null
    startVideoSender((frame) => window.mesh.sendCallVideo(frame), facing)
      .then((result) => {
        if (cancelled) {
          result.stop()
          return
        }
        handle = result
        setLocalStream(result.stream)
      })
      .catch((err) => {
        setVideoOn(false)
        toastRef.current(`Camera unavailable: ${err instanceof Error ? err.message : String(err)}`, 'error')
      })
    return () => {
      cancelled = true
      handle?.stop()
      setLocalStream(null)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [call?.id, call?.state, videoOn, facing])

  useEffect(() => {
    if (localVideoRef.current) localVideoRef.current.srcObject = localStream
  }, [localStream])

  // Incoming ringtone (best effort, short two-tone pattern).
  useEffect(() => {
    if (!incomingCall) return
    let context: AudioContext | null = null
    let interval: number | null = null
    try {
      context = new AudioContext()
      let high = false
      const beep = (): void => {
        if (!context) return
        const oscillator = context.createOscillator()
        const gain = context.createGain()
        oscillator.type = 'sine'
        oscillator.frequency.value = high ? 880 : 660
        high = !high
        gain.gain.setValueAtTime(0.0001, context.currentTime)
        gain.gain.exponentialRampToValueAtTime(0.045, context.currentTime + 0.02)
        gain.gain.exponentialRampToValueAtTime(0.0001, context.currentTime + 0.3)
        oscillator.connect(gain)
        gain.connect(context.destination)
        oscillator.start()
        oscillator.stop(context.currentTime + 0.32)
      }
      beep()
      interval = window.setInterval(beep, 1600)
    } catch {
      /* audio alert is optional */
    }
    return () => {
      if (interval !== null) window.clearInterval(interval)
      void context?.close().catch(() => undefined)
    }
  }, [incomingCall?.id])

  if (!call) return null

  const elapsed = isActive ? Math.max(0, now - call.startedAt) : 0
  const heading = incoming
    ? 'INCOMING LOCAL CALL'
    : isRingingOutgoing
      ? 'CALLING...'
      : call.isVideo
        ? 'HD VIDEO CALL ACTIVE'
        : 'VOICE SESSION ACTIVE'

  return (
    <div className={`call-overlay${showVideo ? ' has-video' : ''}`} role="dialog" aria-modal="true" aria-label="Call">
      <div className="call-top">
        <span className="call-heading">
          <ShieldCheck size={15} /> {heading}
        </span>
        <span className="call-ip">IP: {call.peerIp}</span>
      </div>

      <div className="call-stage">
        {showVideo && (
          <div className="remote-video">
            {remoteFrameUrl ? (
              <img src={remoteFrameUrl} alt="Remote video" />
            ) : (
              <div className="remote-video-placeholder">
                <Camera size={40} />
                <span>Waiting for video…</span>
              </div>
            )}
          </div>
        )}

        <div className={`call-identity${showVideo ? ' compact' : ''}`}>
          <div className={`call-avatar${incoming ? ' ringing' : ''}`}>
            <Avatar name={call.peerName} size={showVideo ? 88 : 118} pulse={incoming || isRingingOutgoing} />
          </div>
          <h2>{call.peerName}</h2>
          <p className="call-subtitle">
            {incoming
              ? call.isVideo
                ? 'Incoming video call'
                : 'Incoming voice call'
              : isRingingOutgoing
                ? 'Waiting for answer…'
                : call.isVideo
                  ? 'Encrypted peer-to-peer media'
                  : 'Direct local audio session'}
          </p>
          {isActive && (
            <div className="call-timer">
              <span className="status-led" /> {formatDuration(elapsed)}
            </div>
          )}
          {micError && <p className="call-warning">Microphone error: {micError}</p>}
        </div>

        {localStream && (
          <div className="local-preview">
            <video ref={localVideoRef} autoPlay playsInline muted />
            <span>YOU</span>
          </div>
        )}
      </div>

      {incoming ? (
        <div className="call-answer-actions">
          <button type="button" className="call-action decline" onClick={onDecline}>
            <span className="call-action-circle">
              <PhoneOff size={26} />
            </span>
            <span>Decline</span>
          </button>
          <button type="button" className="call-action accept" onClick={onAccept}>
            <span className="call-action-circle">
              <Phone size={26} />
            </span>
            <span>Accept</span>
          </button>
        </div>
      ) : (
        <div className="call-controls">
          <CallControl
            label={muted ? 'Unmute Microphone' : 'Mute Microphone'}
            active={muted}
            onClick={() => setMuted((value) => !value)}
            disabled={!isActive}
          >
            {muted ? <MicOff size={22} /> : <Mic size={22} />}
          </CallControl>
          <CallControl
            label={speaker ? 'Switch to Earpiece' : 'Switch to Loudspeaker'}
            active={!speaker}
            onClick={() => setSpeaker((value) => !value)}
            disabled={!isActive}
          >
            {speaker ? <Volume2 size={22} /> : <VolumeX size={22} />}
          </CallControl>
          <CallControl
            label="Toggle Video"
            active={videoOn}
            onClick={() => setVideoOn((value) => !value)}
            disabled={!isActive}
          >
            {videoOn ? <Video size={22} /> : <VideoOff size={22} />}
          </CallControl>
          {videoOn && (
            <CallControl
              label="Switch Camera"
              onClick={() => setFacing((value) => (value === 'user' ? 'environment' : 'user'))}
              disabled={!isActive || !localStream}
            >
              <RefreshCw size={21} />
            </CallControl>
          )}
          <button type="button" className="call-hangup" onClick={onEnd}>
            <PhoneOff size={25} />
            <span>End Call</span>
          </button>
        </div>
      )}

      <div className="call-footnote">
        Audio UDP {8890} • Video UDP {8889} • Raw LAN media, no cloud relay
      </div>
    </div>
  )
}

function CallControl({
  children,
  label,
  active = false,
  disabled = false,
  onClick
}: {
  children: React.ReactNode
  label: string
  active?: boolean
  disabled?: boolean
  onClick: () => void
}): React.JSX.Element {
  return (
    <button
      type="button"
      className={`call-control${active ? ' active' : ''}`}
      onClick={onClick}
      disabled={disabled}
      title={label}
    >
      <span>{children}</span>
      <small>{label}</small>
    </button>
  )
}

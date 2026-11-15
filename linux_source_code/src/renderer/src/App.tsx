import { useCallback, useEffect, useRef, useState } from 'react'
import { Radio, ShieldCheck } from 'lucide-react'
import type { AppEvent, CallInfo, FileProgress, Message, Snapshot } from '../../shared/types'
import { levelsFromPcm, mixLevels, PcmPlayer, startCapture, toBytes } from './audio'
import { TopBar } from './components/TopBar'
import { BottomNav, type Tab } from './components/BottomNav'
import { Toasts, TransferTray, type ToastItem } from './components/Overlays'
import { ChatsPage } from './pages/ChatsPage'
import { BroadcastPage } from './pages/BroadcastPage'
import { WalkiePage } from './pages/WalkiePage'
import { SettingsSheet } from './components/SettingsSheet'
import { CallOverlay } from './components/CallOverlay'

const IDLE_LEVELS = [0.18, 0.42, 0.27, 0.64, 0.36, 0.5, 0.22, 0.31, 0.18, 0.46, 0.61, 0.34, 0.52, 0.24, 0.4, 0.16]

export default function App(): React.JSX.Element {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null)
  const [tab, setTab] = useState<Tab>('chats')
  const [activeChatId, setActiveChatId] = useState<string | null>(null)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [toasts, setToasts] = useState<ToastItem[]>([])
  const [transfers, setTransfers] = useState<Record<string, FileProgress>>({})
  const [remoteFrameUrl, setRemoteFrameUrl] = useState<string | null>(null)
  const [pttActive, setPttActive] = useState(false)
  const [pttReceiving, setPttReceiving] = useState(false)
  const [levels, setLevels] = useState<number[]>(IDLE_LEVELS)

  const snapshotRef = useRef<Snapshot | null>(null)
  const activeChatRef = useRef<string | null>(null)
  const tabRef = useRef<Tab>('chats')
  const listeningRef = useRef(true)
  const transmittingRef = useRef(false)
  const pttStopRef = useRef<(() => void) | null>(null)
  const pttPlayerRef = useRef<PcmPlayer | null>(null)
  const callPlayerRef = useRef<PcmPlayer | null>(null)
  const activeCallRef = useRef<CallInfo | null>(null)
  const remoteUrlRef = useRef<string | null>(null)
  const receivingTimerRef = useRef<number | null>(null)
  const levelRef = useRef<number[]>(IDLE_LEVELS)
  const levelUpdateAtRef = useRef(0)
  const toastIdRef = useRef(0)
  const mountedRef = useRef(true)

  const showToast = useCallback((message: string, tone: 'info' | 'success' | 'error' = 'info'): void => {
    if (!mountedRef.current) return
    const id = ++toastIdRef.current
    setToasts((current) => [...current.slice(-3), { id, message, tone }])
    window.setTimeout(() => {
      if (mountedRef.current) setToasts((current) => current.filter((toast) => toast.id !== id))
    }, 4200)
  }, [])

  const clearRemoteFrame = useCallback((): void => {
    if (remoteUrlRef.current) {
      URL.revokeObjectURL(remoteUrlRef.current)
      remoteUrlRef.current = null
    }
    setRemoteFrameUrl(null)
  }, [])

  const applySnapshot = useCallback(
    (next: Snapshot): void => {
      snapshotRef.current = next
      listeningRef.current = next.settings.listeningEnabled
      activeCallRef.current = next.activeCall
      setSnapshot(next)
      if (!next.activeCall && !next.incomingCall) {
        callPlayerRef.current?.clear()
      }
      if (!next.activeCall && !next.incomingCall) clearRemoteFrame()
      const chat = activeChatRef.current
      if (chat) {
        const peer = next.peers.find((item) => item.ipAddress === chat || item.id === chat)
        if (peer && peer.unreadCount > 0) void window.mesh.markChatRead(chat)
      }
    },
    [clearRemoteFrame]
  )

  const updateLevels = useCallback((pcm: Uint8Array): void => {
    const next = levelsFromPcm(pcm)
    levelRef.current = mixLevels(levelRef.current, next)
    const now = performance.now()
    if (now - levelUpdateAtRef.current < 55) return
    levelUpdateAtRef.current = now
    if (tabRef.current === 'walkie' || transmittingRef.current) setLevels([...levelRef.current])
  }, [])

  const reportAudioError = useCallback(
    (message: string): void => {
      showToast(`Audio playback: ${message}`, 'error')
    },
    [showToast]
  )

  const ensurePttPlayer = useCallback((): PcmPlayer => {
    if (!pttPlayerRef.current) pttPlayerRef.current = new PcmPlayer(reportAudioError)
    return pttPlayerRef.current
  }, [reportAudioError])

  const ensureCallPlayer = useCallback((): PcmPlayer => {
    if (!callPlayerRef.current) callPlayerRef.current = new PcmPlayer(reportAudioError)
    return callPlayerRef.current
  }, [reportAudioError])

  // Unlock the audio pipeline on the first user gesture so incoming PTT and
  // call audio is never queued behind a suspended AudioContext.
  useEffect(() => {
    const prime = (): void => {
      void ensurePttPlayer().resume().catch(() => undefined)
      void ensureCallPlayer().resume().catch(() => undefined)
    }
    window.addEventListener('pointerdown', prime, { once: true })
    window.addEventListener('keydown', prime, { once: true })
    return () => {
      window.removeEventListener('pointerdown', prime)
      window.removeEventListener('keydown', prime)
    }
  }, [ensurePttPlayer, ensureCallPlayer])

  const startPtt = useCallback(async (): Promise<void> => {
    if (transmittingRef.current) return
    if (activeCallRef.current) {
      showToast('End the active call before using the walkie-talkie', 'error')
      return
    }
    transmittingRef.current = true
    setPttActive(true)
    setPttReceiving(false)
    await window.mesh.startPtt()
    try {
      const capture = await startCapture((pcm) => {
        window.mesh.sendPttAudio(pcm)
        updateLevels(new Uint8Array(pcm))
      })
      if (!transmittingRef.current) {
        capture.stop()
        return
      }
      pttStopRef.current = capture.stop
    } catch (err) {
      transmittingRef.current = false
      setPttActive(false)
      void window.mesh.stopPtt()
      showToast(`Microphone unavailable: ${err instanceof Error ? err.message : String(err)}`, 'error')
    }
  }, [showToast, updateLevels])

  const stopPtt = useCallback((): void => {
    if (!transmittingRef.current) return
    transmittingRef.current = false
    setPttActive(false)
    pttStopRef.current?.()
    pttStopRef.current = null
    void window.mesh.stopPtt()
  }, [])

  useEffect(() => {
    const handleBlur = (): void => stopPtt()
    window.addEventListener('blur', handleBlur)
    return () => window.removeEventListener('blur', handleBlur)
  }, [stopPtt])

  const openChat = useCallback((chatId: string | null): void => {
    activeChatRef.current = chatId
    setActiveChatId(chatId)
    if (chatId) void window.mesh.markChatRead(chatId)
  }, [])

  const changeTab = useCallback((next: Tab): void => {
    tabRef.current = next
    setTab(next)
    if (next !== 'walkie') {
      levelRef.current = IDLE_LEVELS
      setLevels(IDLE_LEVELS)
    }
  }, [])

  useEffect(() => {
    mountedRef.current = true
    tabRef.current = tab
  }, [tab])

  useEffect(() => {
    if (!window.mesh) return
    let alive = true
    const unsubscribers: Array<() => void> = []

    void window.mesh
      .getSnapshot()
      .then((initial) => {
        if (alive) applySnapshot(initial)
      })
      .catch(() => showToast('Could not read MeshTalk state', 'error'))

    unsubscribers.push(
      window.mesh.onEvent((event: AppEvent) => {
        if (event.type === 'snapshot') {
          applySnapshot(event.snapshot)
        } else if (event.type === 'toast') {
          showToast(event.message, event.tone)
        } else if (event.type === 'file-progress') {
          const progress = event.progress
          setTransfers((current) => ({ ...current, [`${progress.transferId}-${progress.direction}`]: progress }))
          if (progress.totalBytes > 0 && progress.sentBytes >= progress.totalBytes) {
            window.setTimeout(() => {
              if (!mountedRef.current) return
              setTransfers((current) => {
                const next = { ...current }
                delete next[`${progress.transferId}-${progress.direction}`]
                return next
              })
            }, 2600)
          }
        } else if (event.type === 'call-video') {
          if (!activeCallRef.current) return
          const bytes = toBytes(event.jpeg)
          const copy = new Uint8Array(bytes)
          const url = URL.createObjectURL(new Blob([copy.buffer], { type: 'image/jpeg' }))
          if (remoteUrlRef.current) URL.revokeObjectURL(remoteUrlRef.current)
          remoteUrlRef.current = url
          setRemoteFrameUrl(url)
        }
      })
    )

    unsubscribers.push(
      window.mesh.onPttAudio((payload) => {
        if (!listeningRef.current || transmittingRef.current) return
        const bytes = toBytes(payload)
        ensurePttPlayer().push(bytes)
        updateLevels(bytes)
        setPttReceiving(true)
        if (receivingTimerRef.current !== null) window.clearTimeout(receivingTimerRef.current)
        receivingTimerRef.current = window.setTimeout(() => {
          setPttReceiving(false)
          levelRef.current = IDLE_LEVELS
          setLevels(IDLE_LEVELS)
        }, 750)
      })
    )

    unsubscribers.push(
      window.mesh.onCallAudio((payload) => {
        if (!activeCallRef.current) return
        ensureCallPlayer().push(toBytes(payload))
      })
    )

    return () => {
      alive = false
      unsubscribers.forEach((unsubscribe) => unsubscribe())
      if (receivingTimerRef.current !== null) window.clearTimeout(receivingTimerRef.current)
      transmittingRef.current = false
      pttStopRef.current?.()
      pttStopRef.current = null
      if (remoteUrlRef.current) URL.revokeObjectURL(remoteUrlRef.current)
      void pttPlayerRef.current?.dispose()
      void callPlayerRef.current?.dispose()
    }
    // Audio/state callbacks are stable; this wires the bridge exactly once.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent): void => {
      if ((event.ctrlKey || event.metaKey) && event.key === ',') {
        event.preventDefault()
        setSettingsOpen(true)
      } else if (event.key === 'Escape' && settingsOpen) {
        setSettingsOpen(false)
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [settingsOpen])

  const onStar = useCallback((message: Message): void => {
    void window.mesh.toggleStar(message.id, !message.starred)
  }, [])

  const onOpenFile = useCallback((filePath: string): void => {
    void window.mesh.openFile(filePath)
  }, [])

  const onShowFile = useCallback((filePath: string): void => {
    void window.mesh.showInFolder(filePath)
  }, [])

  if (!snapshot) {
    return (
      <div className="app boot-screen">
        <div className="boot-card">
          <div className="boot-logo">
            <Radio size={38} />
          </div>
          <h1>MeshTalk</h1>
          <p>Scanning the local mesh…</p>
          <div className="boot-dots">
            <span />
            <span />
            <span />
          </div>
          <small>
            <ShieldCheck size={13} /> LAN-only • no accounts • no cloud
          </small>
        </div>
      </div>
    )
  }

  const unread = snapshot.peers.reduce((sum, peer) => sum + peer.unreadCount, 0)
  const onlinePeers = snapshot.peers.filter((peer) => peer.isOnline).length
  const call = snapshot.activeCall ?? snapshot.incomingCall

  return (
    <div className={`app${snapshot.settings.oledTheme ? ' oled' : ''}`}>
      <TopBar
        snapshot={snapshot}
        onEdit={() => setSettingsOpen(true)}
        onToggleTheme={() => void window.mesh.setOledTheme(!snapshot.settings.oledTheme)}
        onOpenSettings={() => setSettingsOpen(true)}
      />

      <main className="content">
        {tab === 'chats' && (
          <ChatsPage
            snapshot={snapshot}
            activeChatId={activeChatId}
            onSelectChat={openChat}
            onStar={onStar}
            onOpenFile={onOpenFile}
            onShowFile={onShowFile}
            onToast={showToast}
          />
        )}
        {tab === 'broadcast' && (
          <BroadcastPage
            snapshot={snapshot}
            onStar={onStar}
            onOpenFile={onOpenFile}
            onShowFile={onShowFile}
            onToast={showToast}
          />
        )}
        {tab === 'walkie' && (
          <WalkiePage
            snapshot={snapshot}
            pttActive={pttActive}
            pttReceiving={pttReceiving}
            levels={levels}
            callActive={Boolean(call)}
            onStartPtt={startPtt}
            onStopPtt={stopPtt}
            onToast={showToast}
          />
        )}
      </main>

      <BottomNav tab={tab} onChange={changeTab} unread={unread} onlinePeers={onlinePeers} />

      <TransferTray transfers={Object.values(transfers)} />
      <Toasts toasts={toasts} onDismiss={(id) => setToasts((current) => current.filter((toast) => toast.id !== id))} />

      {settingsOpen && (
        <SettingsSheet snapshot={snapshot} onClose={() => setSettingsOpen(false)} onToast={showToast} />
      )}

      {call && (
        <CallOverlay
          activeCall={snapshot.activeCall}
          incomingCall={snapshot.incomingCall}
          remoteFrameUrl={remoteFrameUrl}
          onAccept={() => void window.mesh.acceptCall()}
          onDecline={() => void window.mesh.declineCall()}
          onEnd={() => void window.mesh.endCall()}
          onToast={showToast}
        />
      )}
    </div>
  )
}

import { useCallback, useEffect, useRef, useState } from 'react'
import { startVoiceRecorder, type RecorderHandle } from '../audio'

export function useVoiceRecorder({
  onComplete,
  onError
}: {
  onComplete: (wav: ArrayBuffer, durationMs: number) => Promise<void> | void
  onError: (message: string) => void
}): {
  recording: boolean
  preparing: boolean
  elapsedMs: number
  start: () => Promise<void>
  stopAndSend: () => Promise<void>
  cancel: () => void
} {
  const [recording, setRecording] = useState(false)
  const [preparing, setPreparing] = useState(false)
  const [elapsedMs, setElapsedMs] = useState(0)
  const handleRef = useRef<RecorderHandle | null>(null)
  const startingRef = useRef(false)
  const timerRef = useRef<number | null>(null)

  const clearTimer = (): void => {
    if (timerRef.current !== null) {
      window.clearInterval(timerRef.current)
      timerRef.current = null
    }
  }

  const cancel = useCallback((): void => {
    startingRef.current = false
    setPreparing(false)
    clearTimer()
    const handle = handleRef.current
    handleRef.current = null
    if (handle) handle.cancel()
    setRecording(false)
    setElapsedMs(0)
  }, [])

  const start = useCallback(async (): Promise<void> => {
    if (startingRef.current || recording) return
    startingRef.current = true
    setPreparing(true)
    try {
      const handle = await startVoiceRecorder()
      if (!startingRef.current) {
        handle.cancel()
        return
      }
      handleRef.current = handle
      setPreparing(false)
      setRecording(true)
      setElapsedMs(0)
      clearTimer()
      timerRef.current = window.setInterval(() => {
        setElapsedMs(handleRef.current?.durationMs() ?? 0)
      }, 200)
    } catch (err) {
      startingRef.current = false
      setPreparing(false)
      const message = err instanceof Error ? err.message : String(err)
      onError(`Microphone unavailable: ${message}`)
    }
  }, [onError, recording])

  const stopAndSend = useCallback(async (): Promise<void> => {
    startingRef.current = false
    setPreparing(false)
    clearTimer()
    const handle = handleRef.current
    handleRef.current = null
    setRecording(false)
    if (!handle) return
    try {
      const result = await handle.stop()
      setElapsedMs(0)
      if (result.durationMs < 300) {
        onError('Voice note too short — hold a little longer')
        return
      }
      await onComplete(result.wav, result.durationMs)
    } catch (err) {
      onError(`Could not finish voice note: ${err instanceof Error ? err.message : String(err)}`)
    }
  }, [onComplete, onError])

  useEffect(() => {
    return () => {
      startingRef.current = false
      clearTimer()
      handleRef.current?.cancel()
      handleRef.current = null
    }
  }, [])

  return { recording, preparing, elapsedMs, start, stopAndSend, cancel }
}

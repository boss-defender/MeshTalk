// AudioWorklet modules are served as real files from public/worklets. Loading
// them from blob: URLs is blocked by the renderer Content-Security-Policy,
// which silently breaks both capture (PTT/calls) and playback.
const WORKLET_DIR = new URL('./worklets/', document.baseURI)

async function addWorklet(context: AudioContext, file: string): Promise<void> {
  const url = new URL(file, WORKLET_DIR).toString()
  try {
    await context.audioWorklet.addModule(url)
  } catch (error) {
    throw new Error(
      `Audio worklet ${file} failed to load: ${error instanceof Error ? error.message : String(error)}`
    )
  }
}

export class PcmPlayer {
  private context?: AudioContext
  private node?: AudioWorkletNode
  private ready?: Promise<void>
  private disposed = false
  private reportedError = false

  constructor(private readonly onError?: (message: string) => void) {}

  async init(): Promise<void> {
    if (!this.ready) this.ready = this.setup()
    return this.ready
  }

  private async setup(): Promise<void> {
    if (this.disposed) return
    const context = new AudioContext({ sampleRate: 16000, latencyHint: 'interactive' })
    this.context = context
    await addWorklet(context, 'pcm-player.js')
    const node = new AudioWorkletNode(context, 'pcm-player', {
      numberOfInputs: 0,
      numberOfOutputs: 1,
      outputChannelCount: [1]
    })
    this.node = node
    node.connect(context.destination)
    if (context.state === 'suspended') await context.resume()
  }

  async resume(): Promise<void> {
    await this.init()
    if (this.context?.state === 'suspended') await this.context.resume()
  }

  push(pcm: Uint8Array): void {
    if (pcm.byteLength === 0) return
    const copy = pcm.slice()
    void this.init()
      .then(() => {
        if (this.disposed) return
        if (this.context?.state === 'suspended') void this.context.resume()
        this.node?.port.postMessage({ pcm: copy.buffer }, [copy.buffer])
      })
      .catch((error: unknown) => {
        if (this.reportedError) return
        this.reportedError = true
        this.onError?.(error instanceof Error ? error.message : String(error))
      })
  }

  clear(): void {
    this.node?.port.postMessage({ type: 'reset' })
  }

  async dispose(): Promise<void> {
    this.disposed = true
    try {
      this.node?.port.postMessage({ type: 'reset' })
      this.node?.disconnect()
    } catch {
      /* ignore */
    }
    try {
      await this.context?.close()
    } catch {
      /* ignore */
    }
    this.node = undefined
    this.context = undefined
  }
}

export interface CaptureHandle {
  stop(): void
}

export async function startCapture(onChunk: (pcm: ArrayBuffer) => void): Promise<CaptureHandle> {
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: {
      channelCount: 1,
      echoCancellation: true,
      noiseSuppression: true,
      autoGainControl: true
    },
    video: false
  })
  const context = new AudioContext({ sampleRate: 16000, latencyHint: 'interactive' })
  try {
    await addWorklet(context, 'pcm-capture.js')
    const source = context.createMediaStreamSource(stream)
    const node = new AudioWorkletNode(context, 'pcm-capture', {
      numberOfInputs: 1,
      numberOfOutputs: 1,
      outputChannelCount: [1]
    })
    const sink = context.createGain()
    sink.gain.value = 0
    node.port.onmessage = (event: MessageEvent) => {
      const pcm = event.data?.pcm as ArrayBuffer | undefined
      if (pcm) onChunk(pcm)
    }
    source.connect(node)
    node.connect(sink)
    sink.connect(context.destination)
    if (context.state === 'suspended') await context.resume()
    return {
      stop(): void {
        stream.getTracks().forEach((track) => track.stop())
        node.port.onmessage = null
        try {
          node.disconnect()
          sink.disconnect()
          source.disconnect()
        } catch {
          /* ignore */
        }
        void context.close().catch(() => undefined)
      }
    }
  } catch (error) {
    stream.getTracks().forEach((track) => track.stop())
    void context.close().catch(() => undefined)
    throw error
  }
}

export interface RecorderHandle {
  stop(): Promise<{ wav: ArrayBuffer; durationMs: number }>
  cancel(): void
  durationMs(): number
}

export async function startVoiceRecorder(): Promise<RecorderHandle> {
  const chunks: Int16Array[] = []
  const startedAt = Date.now()
  let stopped = false
  const capture = await startCapture((pcm) => {
    if (stopped) return
    chunks.push(new Int16Array(pcm.slice(0)))
  })
  return {
    durationMs: () => Date.now() - startedAt,
    async stop(): Promise<{ wav: ArrayBuffer; durationMs: number }> {
      stopped = true
      capture.stop()
      return { wav: encodeWav(chunks), durationMs: Date.now() - startedAt }
    },
    cancel(): void {
      stopped = true
      capture.stop()
    }
  }
}

export function encodeWav(chunks: Int16Array[]): ArrayBuffer {
  const sampleCount = chunks.reduce((sum, chunk) => sum + chunk.length, 0)
  const buffer = new ArrayBuffer(44 + sampleCount * 2)
  const view = new DataView(buffer)
  const writeString = (offset: number, text: string): void => {
    for (let i = 0; i < text.length; i++) view.setUint8(offset + i, text.charCodeAt(i))
  }
  writeString(0, 'RIFF')
  view.setUint32(4, 36 + sampleCount * 2, true)
  writeString(8, 'WAVE')
  writeString(12, 'fmt ')
  view.setUint32(16, 16, true)
  view.setUint16(20, 1, true)
  view.setUint16(22, 1, true)
  view.setUint32(24, 16000, true)
  view.setUint32(28, 32000, true)
  view.setUint16(32, 2, true)
  view.setUint16(34, 16, true)
  writeString(36, 'data')
  view.setUint32(40, sampleCount * 2, true)
  let offset = 44
  for (const chunk of chunks) {
    for (let i = 0; i < chunk.length; i++) {
      view.setInt16(offset, chunk[i], true)
      offset += 2
    }
  }
  return buffer
}

export interface VideoSenderHandle {
  stream: MediaStream
  stop(): void
}

export async function startVideoSender(
  onFrame: (jpeg: ArrayBuffer) => void,
  facingMode: 'user' | 'environment' = 'user'
): Promise<VideoSenderHandle> {
  const stream = await navigator.mediaDevices.getUserMedia({
    video: {
      width: { ideal: 480 },
      height: { ideal: 360 },
      frameRate: { ideal: 15, max: 20 },
      facingMode: { ideal: facingMode }
    },
    audio: false
  })
  const video = document.createElement('video')
  video.muted = true
  video.playsInline = true
  video.srcObject = stream
  await new Promise<void>((resolve) => {
    const timeout = window.setTimeout(() => resolve(), 1500)
    video.onloadedmetadata = () => {
      window.clearTimeout(timeout)
      resolve()
    }
  })
  await video.play().catch(() => undefined)
  const canvas = document.createElement('canvas')
  canvas.width = 480
  canvas.height = 360
  const context = canvas.getContext('2d')
  if (!context) {
    stream.getTracks().forEach((track) => track.stop())
    throw new Error('Canvas is unavailable')
  }
  let stopped = false
  let busy = false
  const timer = window.setInterval(() => {
    if (stopped || busy || video.readyState < 2) return
    busy = true
    try {
      const vw = video.videoWidth || 640
      const vh = video.videoHeight || 480
      const targetAspect = 4 / 3
      let sw = vw
      let sh = vw / targetAspect
      if (sh > vh) {
        sh = vh
        sw = vh * targetAspect
      }
      context.drawImage(video, (vw - sw) / 2, (vh - sh) / 2, sw, sh, 0, 0, 480, 360)
      canvas.toBlob(
        (blob) => {
          if (!blob || stopped) {
            busy = false
            return
          }
          void blob
            .arrayBuffer()
            .then((frame) => {
              if (!stopped) onFrame(frame)
            })
            .finally(() => {
              busy = false
            })
        },
        'image/jpeg',
        0.35
      )
    } catch {
      busy = false
    }
  }, 100)
  return {
    stream,
    stop(): void {
      stopped = true
      window.clearInterval(timer)
      stream.getTracks().forEach((track) => track.stop())
      video.srcObject = null
    }
  }
}

export function toBytes(payload: Uint8Array | ArrayBuffer | number[]): Uint8Array {
  if (payload instanceof Uint8Array) return payload
  if (payload instanceof ArrayBuffer) return new Uint8Array(payload)
  return new Uint8Array(payload)
}

export function levelsFromPcm(pcm: Uint8Array, bars = 16, gain = 3.4): number[] {
  const samples = new Int16Array(pcm.buffer, pcm.byteOffset, Math.floor(pcm.byteLength / 2))
  const levels: number[] = []
  if (samples.length === 0) return new Array(bars).fill(0.06)
  const bucket = Math.max(1, Math.floor(samples.length / bars))
  for (let bar = 0; bar < bars; bar++) {
    let sum = 0
    let count = 0
    const start = bar * bucket
    const end = Math.min(samples.length, start + bucket)
    for (let i = start; i < end; i++) {
      const value = samples[i] / 32768
      sum += value * value
      count++
    }
    const rms = count > 0 ? Math.sqrt(sum / count) : 0
    levels.push(Math.min(1, Math.max(0.06, rms * gain)))
  }
  return levels
}

export function mixLevels(current: number[], next: number[]): number[] {
  return current.map((value, index) => value * 0.45 + (next[index] ?? 0.06) * 0.55)
}

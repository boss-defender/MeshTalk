class CaptureProcessor extends AudioWorkletProcessor {
  constructor() {
    super()
    this.targetRate = 16000
    this.ratio = sampleRate / this.targetRate
    this.pos = 0
    this.out = []
    this.peak = 0
  }

  process(inputs) {
    const channel = inputs[0] && inputs[0][0]
    if (!channel) return true
    let pos = this.pos
    while (pos < channel.length) {
      const index = Math.floor(pos)
      const fraction = pos - index
      const first = channel[index] || 0
      const second = index + 1 < channel.length ? channel[index + 1] : first
      const sample = first + (second - first) * fraction
      const magnitude = Math.abs(sample)
      if (magnitude > this.peak) this.peak = magnitude
      this.out.push(sample)
      if (this.out.length >= 320) {
        const pcm = new Int16Array(this.out.length)
        for (let i = 0; i < pcm.length; i++) {
          const value = Math.max(-1, Math.min(1, this.out[i]))
          pcm[i] = value < 0 ? value * 32768 : value * 32767
        }
        this.port.postMessage({ pcm: pcm.buffer, peak: this.peak }, [pcm.buffer])
        this.out = []
        this.peak = 0
      }
      pos += this.ratio
    }
    this.pos = pos - channel.length
    return true
  }
}

registerProcessor('pcm-capture', CaptureProcessor)

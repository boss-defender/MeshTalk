class PcmPlayerProcessor extends AudioWorkletProcessor {
  constructor() {
    super()
    this.queue = []
    this.current = null
    this.offset = 0
    this.waiting = true
    this.port.onmessage = (event) => {
      const data = event.data
      if (data && data.type === 'reset') {
        this.queue = []
        this.current = null
        this.offset = 0
        this.waiting = true
        return
      }
      if (data && data.pcm) {
        const pcm = new Int16Array(data.pcm)
        const samples = new Float32Array(pcm.length)
        for (let i = 0; i < pcm.length; i++) {
          samples[i] = pcm[i] < 0 ? pcm[i] / 32768 : pcm[i] / 32767
        }
        this.queue.push(samples)
        if (this.queue.length > 120) this.queue.splice(0, this.queue.length - 120)
      }
    }
  }

  process(_inputs, outputs) {
    const out = outputs[0] && outputs[0][0]
    if (!out) return true
    out.fill(0)
    if (this.waiting) {
      if (this.queue.length < 2) return true
      this.waiting = false
    }
    let written = 0
    while (written < out.length) {
      if (!this.current) {
        this.current = this.queue.shift() || null
        this.offset = 0
        if (!this.current) {
          this.waiting = true
          break
        }
      }
      const count = Math.min(out.length - written, this.current.length - this.offset)
      out.set(this.current.subarray(this.offset, this.offset + count), written)
      written += count
      this.offset += count
      if (this.offset >= this.current.length) this.current = null
    }
    return true
  }
}

registerProcessor('pcm-player', PcmPlayerProcessor)

import os from 'node:os'
import path from 'node:path'
import fs from 'node:fs'
import { execFileSync } from 'node:child_process'
import { app } from 'electron'

export interface IfaceInfo {
  name: string
  address: string
  netmask: string
  broadcast: string
  internal: boolean
}

export function listInterfaces(): IfaceInfo[] {
  const result: IfaceInfo[] = []
  const interfaces = os.networkInterfaces()
  for (const [name, entries] of Object.entries(interfaces)) {
    for (const entry of entries ?? []) {
      if (entry.family !== 'IPv4') continue
      result.push({
        name,
        address: entry.address,
        netmask: entry.netmask,
        broadcast: computeBroadcast(entry.address, entry.netmask),
        internal: entry.internal
      })
    }
  }
  return result
}

export function computeBroadcast(address: string, netmask: string): string {
  try {
    const ip = address.split('.').map(Number)
    const mask = netmask.split('.').map(Number)
    if (ip.length !== 4 || mask.length !== 4) return '255.255.255.255'
    return ip.map((part, i) => (part & mask[i]) | (~mask[i] & 255)).join('.')
  } catch {
    return '255.255.255.255'
  }
}

export function getPrimaryIp(): string {
  const candidates = listInterfaces().filter(
    (i) => !i.internal && !i.address.startsWith('169.254.') && i.address !== '127.0.0.1'
  )
  // Prefer a real wireless/wired NIC, then anything that is not a virtual
  // bridge, and only then accept veth/docker adapters (containers, VMs, WSL).
  const preferred =
    candidates.find((i) => /^(wl|wlan|wifi)/i.test(i.name)) ??
    candidates.find((i) => /^(en|eth)/i.test(i.name)) ??
    candidates.find((i) => !/^(docker|veth|br-|virbr|vmnet|tun|tap|bond)/i.test(i.name)) ??
    candidates[0]
  return preferred?.address ?? '127.0.0.1'
}

/** A local IP that other LAN devices can actually reach, or '' when unknown. */
export function getUsableLocalIp(): string {
  const ip = getPrimaryIp()
  if (!ip || ip === '127.0.0.1' || ip.startsWith('169.254.')) return ''
  return ip
}

export function getBroadcastTargets(): string[] {
  const targets = new Set<string>(['255.255.255.255', '239.255.0.1'])
  for (const iface of listInterfaces()) {
    if (!iface.internal && iface.broadcast !== '255.255.255.255') targets.add(iface.broadcast)
  }
  return [...targets]
}

export function getSubnetHosts(): string[] {
  const primary = getPrimaryIp()
  const iface = listInterfaces().find((i) => i.address === primary)
  if (!iface) return []
  const parts = primary.split('.').map(Number)
  if (parts.length !== 4) return []
  const hosts: string[] = []
  for (let i = 1; i <= 254; i++) {
    const ip = `${parts[0]}.${parts[1]}.${parts[2]}.${i}`
    if (ip !== primary) hosts.push(ip)
  }
  return hosts
}

export function getWifiSsid(): string {
  try {
    const ssid = execFileSync('iwgetid', ['-r'], { timeout: 1500, encoding: 'utf8' }).trim()
    if (ssid) return ssid
  } catch {
    /* ignore */
  }
  try {
    const output = execFileSync('nmcli', ['-t', '-f', 'active,ssid', 'dev', 'wifi'], {
      timeout: 1500,
      encoding: 'utf8'
    })
    const active = output
      .split('\n')
      .map((line) => line.split(':'))
      .find(([state, ssid]) => state === 'yes' && ssid)
    if (active?.[1]) return active[1]
  } catch {
    /* ignore */
  }
  const primary = listInterfaces().find((i) => i.address === getPrimaryIp())
  return primary ? primary.name : 'Wired LAN'
}

export function getStoragePath(): string {
  const downloads = safeDownloadsPath()
  const target = path.join(downloads, 'MeshTalkFiles')
  try {
    fs.mkdirSync(target, { recursive: true })
  } catch {
    /* ignore */
  }
  return target
}

function safeDownloadsPath(): string {
  try {
    return app.getPath('downloads')
  } catch {
    return path.join(os.homedir(), 'Downloads')
  }
}

export function uniqueFilePath(dir: string, fileName: string): string {
  const safe = fileName.replace(/[^a-zA-Z0-9._ -]/g, '_').replace(/^\.+/, '') || 'file'
  const ext = path.extname(safe)
  const base = path.basename(safe, ext)
  let candidate = path.join(dir, safe)
  let index = 1
  while (fs.existsSync(candidate)) {
    candidate = path.join(dir, `${base} (${index})${ext}`)
    index += 1
  }
  return candidate
}

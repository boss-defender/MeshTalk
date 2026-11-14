import { contextBridge, ipcRenderer } from 'electron'
import type { AppEvent, MeshApi, SendVoiceNotePayload, Snapshot } from '../shared/types'

function subscribe<T>(channel: string, listener: (payload: T) => void): () => void {
  const wrapped = (_event: Electron.IpcRendererEvent, payload: T): void => listener(payload)
  ipcRenderer.on(channel, wrapped)
  return () => ipcRenderer.removeListener(channel, wrapped)
}

const api: MeshApi = {
  getSnapshot: () => ipcRenderer.invoke('app:snapshot') as Promise<Snapshot>,
  onEvent: (listener: (event: AppEvent) => void) => subscribe<AppEvent>('app:event', listener),
  onPttAudio: (listener: (pcm: Uint8Array) => void) => subscribe<Uint8Array>('ptt:audio', listener),
  onCallAudio: (listener: (pcm: Uint8Array) => void) => subscribe<Uint8Array>('call:audio', listener),
  onCallVideo: (listener: (jpeg: Uint8Array) => void) => subscribe<Uint8Array>('call:video', listener),

  updateDisplayName: (name: string) => ipcRenderer.invoke('settings:name', name),
  setOledTheme: (enabled: boolean) => ipcRenderer.invoke('settings:oled', enabled),
  setAutoDelete: (enabled: boolean) => ipcRenderer.invoke('settings:auto-delete', enabled),
  cleanUpNow: () => ipcRenderer.invoke('settings:cleanup') as Promise<number>,
  setChannel: (channel: number) => ipcRenderer.invoke('settings:channel', channel),
  setListening: (enabled: boolean) => ipcRenderer.invoke('settings:listening', enabled),

  sendText: (chatId: string, content: string) => ipcRenderer.invoke('chat:send-text', chatId, content),
  sendFile: (chatId: string) => ipcRenderer.invoke('chat:send-file', chatId),
  sendVoiceNote: (payload: SendVoiceNotePayload) => ipcRenderer.invoke('chat:send-voice', payload),
  connectManual: (ip: string) => ipcRenderer.invoke('chat:connect-ip', ip) as Promise<boolean>,
  markChatRead: (chatId: string) => ipcRenderer.invoke('chat:mark-read', chatId),
  toggleStar: (messageId: string, starred: boolean) => ipcRenderer.invoke('chat:star', messageId, starred),

  startPtt: () => ipcRenderer.invoke('ptt:start'),
  stopPtt: () => ipcRenderer.invoke('ptt:stop'),
  sendPttAudio: (pcm: ArrayBuffer) => ipcRenderer.send('ptt:audio', pcm),
  sendCallAudio: (pcm: ArrayBuffer) => ipcRenderer.send('call:audio', pcm),
  sendCallVideo: (jpeg: ArrayBuffer) => ipcRenderer.send('call:video', jpeg),
  initiateCall: (peerIp: string, isVideo: boolean) => ipcRenderer.invoke('call:initiate', peerIp, isVideo),
  acceptCall: () => ipcRenderer.invoke('call:accept'),
  declineCall: () => ipcRenderer.invoke('call:decline'),
  endCall: () => ipcRenderer.invoke('call:end'),

  openFile: (filePath: string) => ipcRenderer.invoke('file:open', filePath),
  showInFolder: (filePath: string) => ipcRenderer.invoke('file:show', filePath),
  openDownloadFolder: () => ipcRenderer.invoke('file:open-downloads')
}

contextBridge.exposeInMainWorld('mesh', api)

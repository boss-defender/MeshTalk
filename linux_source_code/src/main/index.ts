import { app, BrowserWindow, Menu, ipcMain, net, protocol, session, shell } from 'electron'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { Controller } from './controller'

app.setName('MeshTalk')
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required')

// Allow the renderer to display received files through a safe, read-only scheme.
protocol.registerSchemesAsPrivileged([
  {
    scheme: 'media',
    privileges: { standard: false, secure: true, supportFetchAPI: true, stream: true }
  }
])

const gotLock = app.requestSingleInstanceLock()
if (!gotLock) {
  app.quit()
}

let mainWindow: BrowserWindow | null = null
let controller: Controller | null = null
let quitting = false

function createWindow(): void {
  mainWindow = new BrowserWindow({
    width: 1180,
    height: 840,
    minWidth: 960,
    minHeight: 680,
    show: false,
    autoHideMenuBar: true,
    backgroundColor: '#1A1C1E',
    title: 'MeshTalk',
    webPreferences: {
      preload: path.join(__dirname, '../preload/index.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      spellcheck: false
    }
  })

  mainWindow.once('ready-to-show', () => {
    mainWindow?.show()
  })

  mainWindow.on('closed', () => {
    mainWindow = null
    controller?.setWindow(null)
  })

  const target = process.env['ELECTRON_RENDERER_URL']
  if (target) {
    void mainWindow.loadURL(target)
  } else {
    void mainWindow.loadFile(path.join(__dirname, '../renderer/index.html'))
  }
}

function registerMediaProtocol(): void {
  protocol.handle('media', (request) => {
    try {
      const url = new URL(request.url)
      const filePath = url.searchParams.get('path')
      if (!filePath) return new Response('Not found', { status: 404 })
      return net.fetch(pathToFileURL(filePath).toString())
    } catch {
      return new Response('Bad request', { status: 400 })
    }
  })
}

function registerIpc(ctrl: Controller): void {
  ipcMain.handle('app:snapshot', () => ctrl.snapshot())

  ipcMain.handle('settings:name', (_e, name: string) => ctrl.updateDisplayName(name))
  ipcMain.handle('settings:oled', (_e, enabled: boolean) => ctrl.setOledTheme(Boolean(enabled)))
  ipcMain.handle('settings:auto-delete', (_e, enabled: boolean) => ctrl.setAutoDelete(Boolean(enabled)))
  ipcMain.handle('settings:cleanup', () => ctrl.cleanUpNow())
  ipcMain.handle('settings:channel', (_e, channel: number) => ctrl.setChannel(Number(channel)))
  ipcMain.handle('settings:listening', (_e, enabled: boolean) => ctrl.setListening(Boolean(enabled)))

  ipcMain.handle('chat:send-text', (_e, chatId: string, content: string) => ctrl.sendText(chatId, content))
  ipcMain.handle('chat:send-file', (_e, chatId: string) => ctrl.sendFile(chatId))
  ipcMain.handle('chat:send-voice', (_e, payload: { chatId: string; wav: ArrayBuffer }) =>
    ctrl.sendVoiceNote(payload.chatId, payload.wav)
  )
  ipcMain.handle('chat:connect-ip', (_e, ip: string) => ctrl.connectManual(ip))
  ipcMain.handle('chat:mark-read', (_e, chatId: string) => ctrl.markChatRead(chatId))
  ipcMain.handle('chat:star', (_e, messageId: string, starred: boolean) => ctrl.toggleStar(messageId, Boolean(starred)))

  ipcMain.handle('ptt:start', () => ctrl.startPtt())
  ipcMain.handle('ptt:stop', () => ctrl.stopPtt())
  ipcMain.on('ptt:audio', (_e, pcm: ArrayBuffer) => ctrl.sendPttAudio(pcm))

  ipcMain.on('call:audio', (_e, pcm: ArrayBuffer) => ctrl.sendCallAudio(pcm))
  ipcMain.on('call:video', (_e, jpeg: ArrayBuffer) => ctrl.sendCallVideo(jpeg))
  ipcMain.handle('call:initiate', (_e, peerIp: string, isVideo: boolean) => ctrl.initiateCall(peerIp, Boolean(isVideo)))
  ipcMain.handle('call:accept', () => ctrl.acceptCall())
  ipcMain.handle('call:decline', () => ctrl.declineCall())
  ipcMain.handle('call:end', () => ctrl.endCall())

  ipcMain.handle('file:open', (_e, filePath: string) => ctrl.openFile(filePath))
  ipcMain.handle('file:show', (_e, filePath: string) => ctrl.showInFolder(filePath))
  ipcMain.handle('file:open-downloads', () => ctrl.openDownloadFolder())
}

app.on('second-instance', () => {
  if (mainWindow) {
    if (mainWindow.isMinimized()) mainWindow.restore()
    mainWindow.show()
    mainWindow.focus()
  }
})

app.whenReady().then(async () => {
  Menu.setApplicationMenu(null)
  registerMediaProtocol()

  session.defaultSession.setPermissionRequestHandler((_webContents, permission, callback) => {
    if (permission === 'media' || permission === 'notifications' || permission === 'clipboard-sanitized-write') {
      callback(true)
      return
    }
    callback(false)
  })

  controller = new Controller()
  registerIpc(controller)
  createWindow()
  controller.setWindow(mainWindow)
  await controller.start()

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow()
      controller?.setWindow(mainWindow)
      void controller?.start()
    }
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})

app.on('before-quit', (event) => {
  if (quitting || !controller) return
  event.preventDefault()
  quitting = true
  void controller
    .stop()
    .catch(() => undefined)
    .finally(() => app.quit())
})

process.on('uncaughtException', (error) => {
  // Keep the UI alive on transient network errors; log for diagnostics.
  console.error('[meshtalk] uncaught', error)
})

process.on('unhandledRejection', (error) => {
  console.error('[meshtalk] unhandled rejection', error)
})

// Ensure the initial window has the expected dark backdrop before React paints.
app.on('browser-window-created', (_event, window) => {
  window.setBackgroundColor('#1A1C1E')
})

// Open external links in the system browser rather than inside the app.
app.on('web-contents-created', (_event, contents) => {
  contents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('http://') || url.startsWith('https://')) {
      void shell.openExternal(url)
    }
    return { action: 'deny' }
  })
})

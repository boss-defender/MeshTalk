# MeshTalk for Linux

A LAN-only Wi-Fi walkie-talkie desktop app: peer discovery, direct text chat, file and
voice-note transfer, broadcast hub, push-to-talk audio, and local audio/video calls.
Built with **Electron 33 + React 19 + TypeScript + electron-vite** and packaged as a
single self-contained **AppImage**.

The wire protocol is byte-compatible with the original Android APK (see the root
`README.md` for the full protocol table), so the Linux app and the Android app — or the
original APK — can talk to each other on the same Wi-Fi network.

## Features

- **Chats & Calls** — nearby devices appear automatically; open a chat to send text,
  photos, videos, arbitrary files, or recorded voice notes. Message state moves
  SENDING → SENT → DELIVERED → READ using `MSG_ACK` / `MSG_READ`.
- **Broadcast Hub** — posts, audio notes and files reach every connected device.
- **Walkie-Talkie** — pick frequency channel CH-1…CH-8, watch the live waveform, and
  hold the big PTT button to transmit raw PCM16LE mono 16 kHz audio over UDP 9999.
- **Calls** — voice and video calls directly over the LAN (UDP 8890 audio, UDP 8889 JPEG
  video), with mute, speaker/earpiece, video toggle, camera switch and a draggable PiP
  local preview.
- **Settings** — nickname, device profile, live network diagnostics, OLED/dark theme,
  auto-cleanup of non-starred messages older than 2 days, and a P2P guide.

## Requirements

- Node.js 18+ and npm (for building from source).
- A working audio output. Microphone and camera are needed for PTT/calls/voice notes.
- All devices on the same LAN; open TCP 8888 and UDP 8887/8889/8890/9999 in the firewall.

## Build and run

```bash
npm install
npm run dev        # electron-vite dev server + Electron
npm run typecheck  # tsc for main/preload and renderer
npm run build      # production output in out/
npm start          # preview the production build
```

## Package the AppImage

```bash
npm run dist
```

Output: `release/MeshTalk-1.0.0-x86_64.AppImage`. Electron, Chromium, Node and the
required runtime libraries are bundled inside the AppImage, so it runs on Arch,
Debian/Ubuntu, Fedora/RHEL and other glibc distributions without installing Node or
this repository's dependencies.

Run it directly:

```bash
chmod +x release/MeshTalk-1.0.0-x86_64.AppImage
./release/MeshTalk-1.0.0-x86_64.AppImage
```

## Source layout

```
src/
├── main/
│   ├── index.ts              Electron lifecycle, single-instance lock, IPC, media://
│   ├── controller.ts         state, chats, files, calls, PTT orchestration
│   ├── store.ts              JSON persistence (~/.config/MeshTalk/meshtalk-store.json)
│   └── network/
│       ├── protocol.ts       TCP 8888 framing, JSON packets, AES-256-CTR, file typing
│       ├── server.ts         TCP messenger, UDP discovery, mDNS, file transfer
│       ├── ptt.ts            UDP 9999 push-to-talk audio
│       ├── call.ts           UDP 8890 PCM audio / 8889 JPEG video
│       └── netinfo.ts        interface/address helpers
├── preload/index.ts          contextBridge API
├── shared/types.ts           protocol + UI types shared by main and renderer
└── renderer/src/
    ├── App.tsx               renderer state and event wiring
    ├── components/           top bar, bottom nav, overlays, call UI, settings sheet
    ├── pages/                chats, broadcast, walkie pages
    └── styles.css            complete dark/OLED theme
```

## Runtime data

- Store: `~/.config/MeshTalk/meshtalk-store.json`
- Received files: `~/Downloads/MeshTalkFiles/`
- Images/video received over the app are served to the renderer through a custom
  `media://` protocol.

## Permissions

Electron requests microphone and camera through the usual OS permission prompts. If PTT
or calls produce no audio/video, check the OS privacy settings for the app and confirm the
firewall rules above. The app deliberately keeps working for chat/file transfer even when
mic or camera is unavailable.

## Troubleshooting audio

If you see peers and text works, but PTT/calls are silent, in this order:

1. **Wi-Fi AP/client isolation** — the most common cause on real networks. Devices reach the
   router but not each other. Use a phone hotspot or turn off client isolation on the AP.
2. **Firewall** — allow inbound UDP `8887`, `9999`, `8890`, `8889` and inbound TCP `8888`
   (commands above), plus multicast `239.255.0.1`.
3. **Different subnets/VLANs** — multicast and broadcast do not cross routers.
4. **OS microphone permission** — without it the app becomes receive-only and shows a
   "Microphone unavailable" toast.

For whoever maintains the code: PTT and call audio use AudioWorklets that **must** be loaded
from real files under `src/renderer/public/worklets/` (referenced via `document.baseURI`),
never from `blob:` URLs — the renderer Content-Security-Policy blocks `blob:` module loads,
which silently disables both capture and playback. PTT frames are sent to each discovered
peer **and** to `239.255.0.1` and the subnet/limited broadcast, exactly like the original APK,
so an empty peer table still reaches the channel.

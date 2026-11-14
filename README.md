# MeshTalk — Wi-Fi Walkie-Talkie

A peer-to-peer walkie-talkie / messenger for the local network. No internet, no accounts,
no servers: devices on the same Wi-Fi or LAN find each other and exchange **text chat, files,
broadcast messages, push-to-talk voice and audio/video calls directly** over the network.

This repository contains:

- **`android/`** — a Kotlin + Jetpack Compose reconstruction of the original app (buildable Gradle project).
- **`linux/`** — a native desktop port built with Electron + React + TypeScript.
- **`releases/`** — prebuilt binaries (APK and Linux AppImage).

The Android and Linux builds speak the **same wire protocol** as the original APK, so a phone
running this app, the Linux desktop app, and the original app can all talk to each other on one LAN.

---

## Download

| Platform | File | Size | Notes |
|----------|------|------|-------|
| Android | [`releases/MeshTalk-1.0.0.apk`](releases/MeshTalk-1.0.0.apk) | ~21 MB | Rebuilt from the source in this repo. Android 7.0+ (minSdk 24). |
| Linux | `MeshTalk-1.0.0-x86_64.AppImage` | ~114 MB | On the [**Releases**](../../releases/latest) page (too large for plain Git). Any glibc distro. |
| Android (reference) | [`releases/MeshTalk-1.0.0-original.apk`](releases/MeshTalk-1.0.0-original.apk) | ~27 MB | The original APK this project studies, kept for comparison. |

> The AppImage exceeds GitHub's 100 MB per-file limit for normal Git, so it is published as a
> **Release asset**. Get it from the **Releases** page, then:
>
> ```bash
> chmod +x MeshTalk-1.0.0-x86_64.AppImage
> ./MeshTalk-1.0.0-x86_64.AppImage
> ```
>
> It bundles Electron/Chromium/Node and its runtime libraries — no extra packages needed on
> Arch, Debian/Ubuntu, Fedora/RHEL and other glibc distributions.

---

## Features

- **Automatic peer discovery** on the LAN (UDP broadcast + multicast + mDNS).
- **One-to-one chat** with delivery (`DELIVERED`) and read (`READ`) receipts.
- **Group / broadcast hub** — posts reach every connected device.
- **File, image, video and voice-note** transfer with progress and an encrypted body.
- **Push-to-talk walkie-talkie** — hold to talk, raw low-latency PCM audio.
- **Voice and video calls** — direct LAN media, no relay server.
- **Dark and OLED themes**, channels, nicknames, message starring and auto-cleanup.

## Screenshots

_Add screenshots here._ (Drop PNGs into a `docs/` folder and reference them, e.g.
`![Chats](docs/chats.png)`.)

---

## How it works

Both apps are self-contained and serverless. On startup a device listens for peers, and every
message or media frame travels straight to the other device over UDP/TCP.

| Purpose | Transport | Port |
|---------|-----------|------|
| Messenger (chat, ACK/READ, call signaling, file header + body) | TCP | 8888 |
| Peer discovery (broadcast + multicast) | UDP | 8887 |
| mDNS / NSD advert (`_walkietalkie._tcp.`) | mDNS | 5353 (system) |
| Push-to-talk raw audio | UDP | 9999 |
| Call video (JPEG 480×360 q35) | UDP | 8889 |
| Call audio (PCM16LE mono 16 kHz) | UDP | 8890 |

- TCP framing: `[marker 1=packet / 2=file][4-byte big-endian JSON length][JSON header]`;
  file bodies continue with `[16-byte IV][AES-256-CTR ciphertext]`.
- AES key is `P2P_TACTICAL_DIRECT_BINARY_KEY`, zero-padded to 32 bytes.
- Discovery broadcasts to `255.255.255.255`, `239.255.0.1` and every subnet broadcast every 5 s.
- PTT sends each PCM frame to known peers **and** to multicast `239.255.0.1` and the subnet
  broadcast, so it works even before the peer table is filled.
- `P2PPacket` JSON: `type, senderId, senderName, targetId, payload, msgId, extraData`.
- Signal types: `CHAT_MSG, MSG_ACK, MSG_READ, FILE_HEADER, FILE_CHUNK, CALL_INVITE,
  CALL_ACCEPT, CALL_DECLINE, CALL_END, SDP_OFFER, SDP_ANSWER, ICE_CANDIDATE, PING, PONG`.
- SDP/ICE are decorative: media is exchanged directly as raw UDP, not through a real WebRTC stack.

### Firewall

Every device needs **inbound UDP 8887 / 8889 / 8890 / 9999** and **inbound TCP 8888** open on
the local network:

```bash
# ufw (Debian/Ubuntu/Arch)
sudo ufw allow 8888/tcp
sudo ufw allow 8887,8889,8890,9999/udp

# firewalld (Fedora/RHEL)
sudo firewall-cmd --add-port=8888/tcp --add-port=8887/udp \
  --add-port=8889/udp --add-port=8890/udp --add-port=9999/udp
```

---

## Build from source

### Android

Requirements: JDK 21 and the Android SDK (platform 35 + build-tools 35.0.0).

```bash
cd android
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

Grant **Microphone**, **Camera** and (Android 13+) **Notification** permissions on first launch.
See [`android/README.md`](android/README.md) for details.

### Linux

Requirements: Node.js 20+ and npm.

```bash
cd linux
npm install
npm run dev        # development
npm run build      # production bundle in out/
npm run dist       # AppImage into linux/release/
```

See [`linux/README.md`](linux/README.md) for details.

---

## Repository layout

```
.
├── README.md
├── android/     Kotlin + Jetpack Compose source (Gradle project)
├── linux/       Electron + React + TypeScript source
└── releases/    Prebuilt binaries
    ├── MeshTalk-1.0.0.apk
    ├── MeshTalk-1.0.0-original.apk
    └── MeshTalk-1.0.0-x86_64.AppImage   (also attached to the Releases page)
```

---

## Troubleshooting

If peers and text chat work but **audio is silent**:

1. **Wi-Fi AP/client isolation** — the most common cause. Devices reach the router but not each
   other. Use a phone hotspot or disable client isolation on the access point.
2. **Firewall** — allow the UDP/TCP ports above.
3. **Different subnets / VLANs** — multicast and broadcast do not cross routers.
4. **OS microphone permission** — without it the app still works, but becomes receive-only.

## License / provenance

This is an educational reconstruction. The original APK — its strings, colors and wire
protocol — belongs to its respective authors. No code from the APK was copied verbatim; the
Android and Linux implementations were rewritten from observed behavior. See
[`README`](README.md) history and the individual sub-project READMEs for build specifics.

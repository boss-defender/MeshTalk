# MeshTalk for Android (reconstructed source)

A Kotlin + Jetpack Compose reconstruction of the original APK
`com.aistudio.walkietalkie.p2pnet.febded` (Java package `com.example`). It implements the
same LAN-only feature set and the same wire protocol as the original: peer discovery,
direct chat, file/voice-note transfer, broadcast hub, push-to-talk audio and local
audio/video calls.

The project is **build-verified**: `./gradlew :app:assembleDebug` produces a working
debug APK. See the repository root `README.md` for the protocol/firewall table shared
with the Linux port.

## Project facts

| | |
|---|---|
| Language | Kotlin + Jetpack Compose (Material 3) |
| Build | Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21, KSP |
| compileSdk / targetSdk | 35 (original APK used 36) |
| minSdk | 24 |
| applicationId | `com.aistudio.walkietalkie.p2pnet.febded` (unchanged) |
| Room DB | `walkietalkie_database`, tables `messages` and `peers` |

## Build

A JDK 21 is required by Gradle 8.9/AGP 8.7.3. A portable toolchain is included in the
repository under `work/tools/android-toolchain/` (Temurin JDK 21, Android command-line
tools, `platform-tools`, `platforms;android-35`, `build-tools;35.0.0`).

```bash
cd android
export JAVA_HOME=/home/boss/Projects/wifi-walkie/work/tools/android-toolchain/jdk-21.0.13+9
export ANDROID_HOME=/home/boss/Projects/wifi-walkie/work/tools/android-toolchain/sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

To build with your own SDK, install platform 35 + build-tools 35.0.0 and set
`ANDROID_HOME` accordingly (or create `local.properties` with `sdk.dir=...`).

## Install and permissions

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch the app asks for:

- **Microphone** — push-to-talk, voice notes and call audio.
- **Camera** — video calls.
- **Notifications** (Android 13+) — the foreground "Walkie Talkie Active" service.

The app still discovers peers and exchanges text/files if mic/camera are denied.

## Audio notes

- **PTT (`PttEngine.kt`)** sends each 20 ms PCM16LE frame to every online peer, the multicast
  group `239.255.0.1`, `255.255.255.255` and each subnet broadcast (with `SO_BROADCAST`), so a
  stale or empty peer table never silences the walkie-talkie. While transmitting it suppresses
  incoming playback to avoid echoing your own broadcast back into the speaker.
- **Calls (`LocalCallEngine.kt`)** use one bound UDP socket per medium — audio `8890` and video
  `8889` — for both sending and receiving, the same shape as the original APK. If a bind fails
  the call aborts cleanly instead of spinning, and audio focus is requested/abandoned around
  the call.
- If you see peers and text works but audio is silent, check **Wi-Fi AP/client isolation** and
  that all devices are on the same subnet; multicast and broadcast do not cross routers.

## Source layout

```
app/src/main/java/com/example/
├── MainActivity.kt                 component activity, runtime permissions
├── MeshTalkApp.kt                  Compose app shell (tabs, dialogs, call overlays)
├── NavTab.kt                       Chats & Calls / Broadcast Hub / Walkie-Talkie
├── data/                           Room entities, DAO, database
├── network/
│   ├── P2PProtocol.kt              signals, JSON packets, constants
│   ├── P2PServer.kt                TCP 8888, UDP 8887 discovery, file transfer
│   ├── PttEngine.kt                UDP 9999 raw PCM16LE PTT
│   ├── LocalCallEngine.kt          UDP 8890 audio / 8889 JPEG video
│   ├── FileCrypto.kt               AES-256-CTR + zero-padded static key
│   ├── NsdHelper.kt                mDNS `_walkietalkie._tcp.`
│   ├── NetworkUtils.kt             IP/subnet/broadcast helpers
│   └── MulticastLockManager.kt     WifiManager multicast lock
├── repository/
│   ├── P2PRepository.kt            state, discovery, chats, files, calls, PTT
│   ├── CallOffer.kt
│   └── P2PConnectionStatus.kt
├── service/WalkieTalkieService.kt  foreground service, wake + high-perf Wi-Fi locks
├── ui/
│   ├── MainViewModel.kt
│   ├── theme/                      colors + Material 3 theme (dark/OLED)
│   ├── components/                 chrome, cards, avatars, message rows
│   └── screens/                    chats, broadcast, walkie, settings, calls
└── util/
    ├── FileStorageManager.kt       Download/MeshTalkFiles with app-specific fallback
    ├── VoiceNoteRecorder.kt        AAC/MPEG-4 voice notes
    └── MediaUtils.kt               SAF copy, FileProvider open/share
```

## Behavior notes preserved from the APK

- Room keys: direct chats use the peer IP address, plus `GLOBAL` and `CHANNEL_n`.
- Incoming `CHAT_MSG` is acknowledged with `MSG_ACK`; opening a chat sends `MSG_READ`.
- Message statuses: `SENDING, SENT, DELIVERED, READ, FAILED, RECEIVED`.
- Auto-delete removes non-starred messages older than 2 days, every 6 hours.
- `CALL_INVITE`/`CALL_ACCEPT`/`CALL_DECLINE`/`CALL_END` plus `SDP_OFFER`, `SDP_ANSWER`,
  `ICE_CANDIDATE` mirror the original; SDP/ICE are decorative and media flows over the
  direct UDP ports.
- Device id `Build.MODEL + "_" + UUID.take(4)`, default name `Walkie-User-<last4>`.
- Palette and UI copy match the original (amber/green/pale-blue accents, OLED variant,
  `Save Nickname`, `Broadcast Send`, `HOLD TO SPEAK`, network status strings, and so on).

## Known differences from the original APK

- Built against SDK 35 rather than 36.
- No Firebase/Google services components (none are needed for LAN operation).
- Voice notes use AAC/MPEG-4 via `MediaRecorder`; the original tries OGG/Opus first and
  falls back to AAC.
- Call media uses a simplified CameraX analysis pipeline that mirrors the original
  JPEG-over-UDP approach rather than a full WebRTC implementation (the original did not
  use WebRTC either).

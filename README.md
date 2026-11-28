<p align="center">
  <img src="master-docs/AppleToWearOS/icon-light.png" width="120" alt="WearBridge icon" />
</p>

# WearBridge

Mirror iPhone notifications to Wear OS watches over Bluetooth Low Energy using Apple's ANCS (Apple Notification Center Service) protocol. Fully local — no cloud relay, no companion phone app required.

```
iPhone (iOS 26)                       Wear OS Watch
┌─────────────────────┐               ┌──────────────────────────┐
│                     │               │                          │
│  System ANCS        │◄──BLE bond──►│  BLE Central / GATT      │
│  (built-in)         │               │  ANCS Protocol Parser    │
│                     │               │  Notification Manager    │
│                     │               │  Foreground Service      │
│                     │               │                          │
└─────────────────────┘               └──────────────────────────┘
```

## Features

- **Real-time notification mirroring** — messages, emails, calls, calendar alerts, and more
- **Apple Watch-style alerts** — round, brand-colored app badge on every alert (Zalo, Messages, Messenger, WhatsApp, Gmail…)
- **Now Playing** — play/pause, next/previous and crown-controlled volume for whatever plays on the iPhone (Apple Media Service)
- **Workouts → Apple Health** — Apple Workout-style app (walk, run, cycle, HIIT, strength, yoga…) with live heart rate, calories, distance, pace and GPS route; saved to Apple Health with route, heart rate and pauses
- **All-day heart rate & steps → Apple Health** (opt-in), queued on the watch until the iPhone app stores them
- **Activity rings** — Move, Exercise and Stand in the app, an Activity tile and a Move complication
- **Workout goals & splits** — time/distance/calorie goals with a progress ring, km split taps, segments
- **Tiles & complications** — one-tap Workout tile, iPhone battery complication
- **Quick replies from your iPhone list** — on the call screen and as reply chips on Messages alerts
- **Native Wear OS UI** — Material 3 for Wear OS, swipe-to-go-back, curved time text, iOS-style switches
- **Notification Settings** — turn future alerts from observed apps on or off and hide message previews. Incoming call screens are separate.
- **75+ app icon mappings** — WhatsApp, Slack, Gmail, Instagram, Telegram, Discord, and many more show their own icons (Material Design, 25% padded for Wear OS)
- **Incoming call overlay** — integrated with Android Telecom framework (ConnectionService) for system-level call UI that shows over charging screen and lock screen, with Answer, Decline, and Quick Reply
- **WhatsApp/FaceTime call detection** — detects VoIP calls via message content heuristics
- **Smart notification filtering** — coalesces updates, prioritizes new alerts, and optionally restores up to 20 outstanding alerts quietly on reconnect
- **Heads-up overlays** — PRIORITY_HIGH + IMPORTANCE_HIGH channels for reliable notification popups
- **Auto-reconnection** — exponential backoff with `autoConnect` for bonded devices
- **Boot persistence** — service auto-starts on watch reboot
- **Haptic feedback** — category-aware vibration patterns via direct Vibrator API (50ms for messages, double-tap for calls)
- **iPhone media controls** — show the track and artist and expose available player commands including volume and skipping over Apple Media Service when the iPhone exposes the service
- **No cloud dependency** — everything runs over local BLE
- **iOS companion app** — single-screen status UI with auto-detection of connected watches, "How it Works" explainer, and iOS 26 icon theming

## WearBridge 1.1 on Pixel Watch

Install the Wear OS APK over the previous WearBridge build. On iPhone enable Share System Notifications under the watch in Bluetooth settings. Enable Messages in Notification Center and permit notifications on the watch. Under Notification Settings, the session counters distinguish events received, alert posts, and response timeouts. Quiet catch-up uses only events iOS republishes. Enable Sync dismissals to mirror a swipe when iOS offers a recognized clear/dismiss action in English or Vietnamese. Some alerts are suppressed by Focus, per-app settings, or the notification channel on the watch.

With the matching iOS companion:

- Use **Draft reply** on a Messages alert, then open **Drafts** on the iPhone, choose the recipient and send. An encrypted write must reach the companion. Drafts are limited by the negotiated Bluetooth packet size and available storage. Text-entry actions require previews to be enabled. The notification title does not identify a verified iMessage recipient or group.
- **Battery & agenda** reads iPhone battery and, after opt-in, up to three events in the next 24 hours. **Ping iPhone** needs permission for local notifications and follows system sound and Focus settings.
- Open **Settings → Open remote camera** on iPhone before using **Camera shutter** on the watch. Allow Camera and Add Photos. The phone displays the viewfinder.
- **Workouts** records timed walks, runs and bike rides offline. Enable workout import in iPhone Settings inside WearBridge, then select **Sync to Apple Health** on the watch. A successful HealthKit write removes the watch copy. The companion requires a provisioning profile with HealthKit for this feature. Only activity type and elapsed duration are transferred.

The watch APK can run without the extra iOS features. The upstream hardware list below predates 1.1. This build requires physical testing for latency, battery life, media-player behavior, background execution and reconnection.

## Upstream tested hardware

| Device | Role |
|--------|------|
| iPhone 17 Pro Max (iOS 26) | Notification source |
| Google Pixel Watch 2 (Wear OS 5) | Primary watch |
| Samsung Galaxy Watch (Wear OS) | Secondary — same APK |
| OnePlus Watch 2 (Wear OS 5) | Community-tested ([#1](https://github.com/k97/WearBridge/issues/1)). No Telecom on this watch, so calls use the app's own call screen |

## How It Works

ANCS is a system-level BLE service built into iOS. Any bonded BLE accessory can subscribe to iPhone notifications without needing an iOS app. The watch acts as a BLE central device and GATT client:

1. Watch scans and bonds with iPhone
2. Subscribes to ANCS characteristics (Data Source, then Notification Source)
3. Receives 8-byte notification events in real-time
4. Fetches notification attributes (title, message, app ID) via Control Point
5. Reassembles fragmented responses and posts native Android notifications

## Project Structure

```
├── wearos-app/          # Wear OS app (Kotlin, Jetpack Compose)
│   └── app/src/main/java/com/wearos/ancsbridge/
│       ├── ble/         # BLE scanning, connection, GATT callbacks
│       ├── ancs/        # ANCS protocol, service, Telecom ConnectionService
│       ├── model/       # Data classes
│       ├── ui/          # Compose UI + Material Icons (main, call screen)
│       └── viewmodel/   # ViewModel
│
├── ios-app/             # iOS companion app (Swift, optional)
│   └── AncsBridge/
│       ├── BLE/         # Peripheral + Central manager, watch detection
│       ├── Views/       # Single-screen status UI, How it Works
│       └── ViewModels/  # BLE view model
│
└── master-docs/         # Research notes and ANCS spec references
```

## Build

### Prebuilt from CI

Every push to `main` and every pull request is built by [GitHub Actions](https://github.com/k97/WearBridge/actions/workflows/ci.yml). Open a green run and download from **Artifacts**:

- `WearBridge-wearos-debug-apk`: install with `adb install -r app-debug.apk`
- `WearBridge-ios-unsigned-ipa`: unsigned, so re-sign it with your own Apple ID in a sideloading tool (AltStore, SideStore, Sideloadly)

Artifacts are kept for 14 days.

### Wear OS App

Needs JDK 17 and the Android SDK (platform 35).

```bash
cd wearos-app
./gradlew testDebugUnitTest assembleDebug
```

Install on watch via ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### iOS Companion App (Optional)

Open `ios-app/AncsBridge.xcodeproj` in Xcode and build. The companion app provides pairing status UI but is **not required** — ANCS works without it once the watch is bonded.

## Setup

1. Install the APK on your Wear OS watch
2. Open the app and tap **Scan**
3. Select your iPhone from the device list
4. Accept the Bluetooth pairing dialog on the iPhone
5. Notifications will start mirroring automatically

## Supported Apps

75+ iOS apps are mapped with custom icons, including:

WhatsApp, iMessage, Gmail, Outlook, Slack, Discord, Telegram, Signal, Instagram, Threads, Facebook Messenger, Google Maps, Apple Music, YouTube, Uber, Uber Eats, Notion, GitHub, LinkedIn, Twitter/X, Apple Calendar, Apple Reminders, Apple Wallet, and many more.

Unknown apps fall back to category-based icons (phone for calls, envelope for email, etc.).

## Roadmap

- [x] **Incoming Call Overlay** — Telecom ConnectionService integration for system-level call UI
- [x] **Quick Reply UI** — 3 preset messages on decline (UI ready, BLE transport pending)
- [x] **Vibration Tuning** — Category-aware haptics via direct Vibrator API (50ms messages, double-tap calls)
- [x] **Notification Icons** — 37 padded Material Design icons + Lucide call icons
- [x] **WhatsApp Call Detection** — Heuristic detection of VoIP calls from WhatsApp/FaceTime
- [x] **Quick Reply BLE Transport** — Decline-with-message text sent to the iOS companion over a custom BLE characteristic
- [ ] **App Icon Support** — Expand to full-color Play Store PNGs as `largeIcon`
- [ ] **Multi-Device Pairing** — Support switching between multiple Wear OS watches
- [ ] **Health Data Bridge** — Transfer Wear OS health/fitness data to iPhone via BLE + HealthKit

## Contributing

Contributions are welcome! Feel free to open issues or submit pull requests. CI runs the Wear OS unit tests and both builds on every pull request. If you have built something in a fork, please open a PR: fork branches aren't visible here unless you do.

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.

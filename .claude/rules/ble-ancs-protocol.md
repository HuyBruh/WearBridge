# BLE & ANCS Protocol Details

## ANCS Characteristics

| Characteristic | UUID | Role |
|---|---|---|
| Notification Source | `9FBF120D-6301-42D9-8C58-25E699A21DBD` | Subscribe — 8-byte event notifications |
| Control Point | `69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9` | Write — request attributes / perform actions |
| Data Source | `22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB` | Subscribe — fragmented attribute responses |

**Subscribe to Data Source BEFORE Notification Source** (Apple requirement).

## BLE Connection Flow

1. Watch scans → finds iPhone (or uses bonded device)
2. `connectGatt()` → `onConnectionStateChange(CONNECTED)`
3. `requestMtu(512)` → `onMtuChanged` → `discoverServices()`
4. ANCS not visible (not bonded) → `createBond()` → iOS pairing dialog
5. `BOND_BONDED` → `discoverServices()` again → ANCS visible
6. Subscribe DS CCCD → Subscribe NS CCCD → session active (guard against double subscription)
7. NS events → fetch attributes via CP → parse DS → post Android notification

## Reconnection

- `autoConnect=true` in `connectGatt()` for bonded devices (chipset whitelist, battery efficient)
- Exponential backoff: 2s → 4s → 8s → … → 30s cap; reset once an ANCS session is active
- All GATT operations are serialized by `runOperation` (see gotcha #20); the session setup (DS → NS → GAP name → companion → AMS) is one coroutine
- `onServiceChanged` (companion app started/stopped) → disconnect and reconnect after 500 ms
- `START_STICKY` service + `BootReceiver` for persistence
- `getBondedIPhone()` matches device names containing "iPhone", "AncsBrid", "AncsBridge", "WearBrid", "WearBridge"
- GAP Device Name characteristic (0x2A00) read after ANCS connects for real iPhone name
- GATT callbacks run on the main thread (`connectGatt` with a main-looper `Handler`), like the coroutines sharing the connection state
- Bluetooth turned back on → immediate reconnect (`ACTION_STATE_CHANGED`); a failed service discovery or ANCS CCCD write drops the link so the normal reconnect starts over; bond events for other devices are ignored
- A link lost while a call rings dismisses the call screen (its REMOVED never comes)

## Silent/Pre-existing Notification Handling

- Pre-existing notifications (`EVENT_FLAG_PRE_EXISTING`) are skipped at NS event level — no GATT writes, no haptics — unless the user turns on "Missed Alerts" (off by default), which posts them on the silent channel
- No time-based grace period: genuinely new alerts right after a reconnect are shown
- **Never use `setNotificationSilent()`** — it kills heads-up overlays on Wear OS

## Notification Channels

Channels are **versioned** (suffix `_v7` etc.) because Android permanently caches channel settings. Bump version in `AncsApplication.kt` to change settings.

Current channels: `service` (LOW), `incoming_call`, `messages`, `email`, `social`, `schedule`, `other` — all at `IMPORTANCE_HIGH` with system-default sound. Vibration handled via direct Vibrator API only.

## Telecom Framework (Incoming Calls)

1. `AncsConnectionService` registers a `PhoneAccount` with `CAPABILITY_SELF_MANAGED`
2. On cat=1 NS event, `TelecomManager.addNewIncomingCall()` is called immediately
3. System creates `AncsCallConnection` via `onCreateIncomingConnection()`
4. Caller name updated when attributes arrive (~1s later)
5. Answer/Decline sends ANCS action back to iPhone
6. Fallback: CallStyle notification + direct `startActivity()` if Telecom rejects

## Call State Dismissal (Three Layers)

When a call is answered on the iPhone, the watch overlay transitions from "Incoming Call" to "Active on iPhone" (auto-dismisses in 5s). Three detection layers:

1. **Companion BLE** (opt-in): `CXCallObserver` → call state characteristic `A0E3D5B4-...` → watch subscribes for notifications. Toggle: Settings → Live Call Status. The state is derived from all incoming calls (a waiting call that rings wins over the active one; outgoing calls are ignored); "ended" turns into idle 2 s later.
2. **ANCS MODIFIED**: NS event with EventID=1 for active call UID triggers overlay transition.
3. **Safety timeout**: 45-second auto-dismiss if no events arrive.

## Apple Media Service (Now Playing)

| Characteristic | UUID | Role |
|---|---|---|
| Remote Command | `9B3C81D8-57B1-4A8A-B8DF-0E56F7CA51C2` | Notify = supported command IDs; write = send command |
| Entity Update | `2F7CABCE-808D-411F-9A0C-BB92BA96C102` | Write `[0,0,1,2]` (player name, playback info, volume) and `[2,0,2]` (artist, title); notify = updates |

Crown rotation on the Now Playing screen sends Volume Up/Down.

`media/PhoneMediaSession` mirrors AMS into a local `MediaSessionCompat` (metadata, playing/paused, remote `VolumeProviderCompat` → AMS volume) with a MediaStyle notification and an Ongoing Activity chip while playing, so Wear OS shows its media controls without opening the app.

## Companion Service Characteristics

| Characteristic | UUID | Role |
|---|---|---|
| Connection Status | `A0E3D5B2-4C6F-4E8A-9B2D-7F1E3A5C8D0B` | Read/Notify — watch reads to signal connection |
| Quick Reply | `A0E3D5B3-4C6F-4E8A-9B2D-7F1E3A5C8D0B` | Write — watch sends `"callerName\|replyText"` |
| Call State | `A0E3D5B4-4C6F-4E8A-9B2D-7F1E3A5C8D0B` | Read/Notify — iPhone pushes call state (0x00=idle, 0x01=ringing, 0x02=active, 0x03=ended) |

| Quick Replies | `A0E3D5B9-4C6F-4E8A-9B2D-7F1E3A5C8D0B` | Read (encrypted) — JSON array of the iPhone app's quick replies; watch caches it in `QuickReplies` and uses it on the call screen and as reply chips |
| Today | `A0E3D5BA-4C6F-4E8A-9B2D-7F1E3A5C8D0B` | Read/Notify/Write (encrypted) — JSON weather (Open-Meteo), next 36 h of events, today's reminders; notify = 1 byte "changed, re-read"; write `{"complete":"<reminder id>"}` |
| Health | `A0E3D5B8-4C6F-4E8A-9B2D-7F1E3A5C8D0B` | Write (encrypted) — chunked health records: `[flags][payload]`, flags 0x01 first, 0x02 last |

## Workouts & Apple Health

- Watch: `health/WorkoutService` runs Health Services `ExerciseClient` (HR, calories, distance, steps, GPS) in a `health|location` foreground service with an Ongoing Activity chip. Workouts ≥ 60 s go to `HealthOutbox` (one JSON file per record in `filesDir/health_outbox`).
- `PassiveHealthService` (opt-in, watch → Apple Health screen) receives all-day `HEART_RATE_BPM` and `STEPS_DAILY`; `StepDeltas` turns daily totals into interval samples.
- `AncsService.syncHealth()` drains the outbox oldest-first whenever the companion service appears (and on `ACTION_SYNC_HEALTH`). A record is deleted only after the iPhone acks its last chunk.
- iPhone: `PeripheralManager.handleHealthChunk` reassembles; `WatchHealthImporter.receive` writes the record to Application Support before acking, then imports into HealthKit (`HKWorkoutBuilder` + samples + pause events + `HKWorkoutRouteBuilder`). Locked iPhone (`errorDatabaseInaccessible`) → retried on `protectedDataDidBecomeAvailable`. Dedupe: workouts by `HKMetadataKeySyncIdentifier` query, samples by per-sample sync identifiers.
- Passive also reads `CALORIES_DAILY`, `DISTANCE_DAILY`, `FLOORS_DAILY`; distance and floors go to Health as interval samples (`DailyDeltas`).
- Workouts support goals (time / distance / calories, ring around the bezel), automatic km splits (haptic + banner) and user segments; iPhone writes splits as `.lap` and segments as `.segment` workout events.
- Activity rings (`ActivityTracker`): Move = `CALORIES_DAILY` minus a resting rate learned from quiet periods; Exercise = workout minutes + brisk walking (≥100 steps/min) outside workouts; Stand = hours with ≥60 steps. Shown in the app, the Activity tile and the Move complication.
- Tiles: `ActivityTileService` (rings), `WorkoutTileService` (one-tap walk/run/cycle). Complications: Move, iPhone battery (parsed from the companion status summary, refreshed every 15 min while connected).
- Sleep: `PassiveListenerConfig.setShouldUserActivityInfoBeRequested(true)`; `SleepTracker` turns ASLEEP→other transitions (≥20 min) into sleep intervals and a resting heart rate (mean of the lowest 30% of readings during sleep) → `sleepAnalysis` / `restingHeartRate`.
- Workouts also record `VO2_MAX`, `GROUND_CONTACT_TIME`, `VERTICAL_OSCILLATION`, `STRIDE_LENGTH` when supported → `vo2Max`, `runningGroundContactTime`, `runningVerticalOscillation`, `runningStrideLength`.
- Today (`model/PhoneInfo`): Today screen, `TodayTileService`, `WeatherComplicationService`, `NextEventComplicationService`; refreshed on connect, on notify, every 15 min.
- Toggle key on iPhone is still `syncWorkouts` (Settings → Apple Health); when off, the first chunk is refused and the watch keeps the record.

## Messages over Bluetooth Classic (MAP + PBAP)

iOS supports MAP 1.4 (reply, compose, browse inbox) and PBAP for car kits. The watch acts as a MAP/PBAP client in userspace:

- One-time Classic bond (`messages/ClassicPairing`): discovery while the iPhone's Bluetooth settings are open, `createBond()`; address saved in prefs `messaging/device`. The BLE (ANCS) bond is separate and unaffected.
- `messages/ObexClient`: OBEX CONNECT / SETPATH / GET / PUT over `createRfcommSocketToServiceRecord` (MAS `0x1132`, PSE `0x112F`); targets MAS `bb582b40-…`, PBAP `796135f0-…`.
- `messages/PhoneMessaging`: SETPATH root → telecom → msg; `GetMessagesListing` on inbox/sent (subject ≤255 chars); `PushMessage` to outbox with Charset=UTF-8 (iOS sends via Messages, iMessage or SMS); `GetMessage` for full text; PBAP pull `telecom/pb.vcf` (vCard 2.1, FN/N/TEL/EMAIL).
- Replying to a Messages alert: the ANCS title (contact name) is matched against inbox `sender_name` to find the address; falls back to PBAP contacts. Missed-call alerts get a Message action too.
- `messages/MapServerRecords` publishes MNS (`0x1133`, with a minimal OBEX server for event reports → refresh) and PCE (`0x112E`) RFCOMM service records like a car kit; iOS reads them at pairing time, so the watch must be (re-)paired after they exist. Setup re-pairs via hidden `removeBond` when possible. OBEX refusals show the response code.
- iPhone side: user must enable **Show Notifications** (MAP) and **Sync Contacts** (PBAP) for the watch in Settings › Bluetooth › ⓘ.
- Watch UI: Messages (conversations, thread bubbles, reply with quick replies or system dictation/keyboard/emoji via `RemoteInputIntentHelper`, compose to contacts), Setup screen.

## Quick Reply Flow

1. Watch: user taps quick reply → ANCS negative action + BLE write to quick reply characteristic
2. iPhone: `PeripheralManager` receives write → `QuickReplyLauncher` opens `shortcuts://run-shortcut?name=WearBridge%20Reply&input=text&text=…` (`&`, `=`, `+` percent-encoded)
3. iOS only opens URLs from the foreground: with the app in the background (the usual case), the reply becomes a local notification and tapping it runs the shortcut
4. User creates iOS Shortcut "WearBridge Reply" to send SMS/iMessage with the input text
5. The reply list edited in the iOS app is read on every connection (A0E3D5B9) and cached on the watch
7. Chat apps (Messenger, WhatsApp, Zalo, Telegram…) get a Reply too: iOS offers accessories no text reply for them, so the reply becomes an iPhone draft with `app`; tapping its notification copies the text and opens the app (URL scheme)
6. Message notifications from Messages get a "Reply" action with those replies as chips (plus voice/keyboard); the text is saved as a draft on the iPhone, which posts a "Reply to …" notification that opens the Messages composer

## iPhone Companion App Watch Detection

The iPhone can't detect ANCS connections (system-level). Instead, `CBCentralManager.retrieveConnectedPeripherals(withServices:)` probes for devices with standard BLE services (Device Info 0x180A, Battery 0x180F, etc.) and filters by known watch name patterns. Polls every 10 seconds.

## App Icon Mapping

75 iOS bundle IDs mapped in `AppIconMapper.kt`. 37 notification icons with 25% padding (viewport 32, translate 4). Call screen uses 3 Lucide stroke icons. Watch UI uses Material Icons Extended.

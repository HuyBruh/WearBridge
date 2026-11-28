# Future Work

## App Icon Support
- Currently 24dp white Material Design vector drawables with 25% padding
- Upgrade to Play Store PNGs as `largeIcon` for full-color icons

## Multi-Device Pairing
- Support pairing with multiple Wear OS watches
- Persist bonded device list and per-watch notification category preferences
- Only one watch active at a time (BLE limitation with ANCS)

## Health Data Bridge (done: workouts, goals, splits, heart rate, steps, distance, floors)
- Done: sleep (duration, from the ASLEEP state), resting heart rate, VO2 max and running form
- Not possible from a third-party Wear OS app: SpO2, HRV, sleep stages (Health Services does not expose them; they stay in Fitbit)
- Activity rings are estimates; Apple's own Activity summaries cannot be written by third-party apps

## Quick Reply List Sync (done: read on connect)
- Push changes immediately (notify) instead of waiting for the next connection

## iOS Shortcut Template
- Provide a pre-built "WearBridge Reply" shortcut for download
- Currently users must create the iOS Shortcut manually to handle quick reply text

## Messages
- Push MAP event reports (MNS server on the watch) instead of re-listing after each alert
- Group chats (MAP listings show one address per message)
- Photos/attachments (MAP GetMessage with Attachment=1)

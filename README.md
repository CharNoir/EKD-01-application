# EKD-01 Control

An unofficial Android app for the **EKD01-TB** e-bike display (sold with the BikeGo app). It talks to the
display over Bluetooth LE, shows live ride data, controls a few display functions, records your rides, and
can optionally record and map your GPS route. Everything stays on your phone.

> This is a personal project built from a reverse-engineered protocol. It is not affiliated with the display's
> manufacturer or the BikeGo app. It writes settings to the display, so use it at your own risk.

## Features

**Live display**
- Speed, battery, trip distance and time, average and maximum speed, odometer.
- Assist level up/down, headlight on/off, brightness, km/miles, auto-off timer.
- Several bikes: scan, name, switch between and forget displays.

**Ride history**
- Every connection is recorded as a ride, with readings at an interval you choose (1 s to 1 h).
- One card per day with distance, riding time, battery used, top and average speed.
- Ride detail with graphs (battery, speed, odometer and the display's own trip counters).
- Riding time, average and top speed are worked out from the recorded readings, not from the display's trip
  counters (the display decides on its own when a "trip" starts and ends).
- Charge detection between rides that ignores the battery's own recovery after hard riding
  (see `RideStats.chargedBetween`).

**GPS routes (optional, off by default)**
- Record your position during rides into the local database.
- Day route and per-ride maps (OpenStreetMap tiles, with Standard, Cycling and Dark styles); the route can be
  coloured by speed. With the map switched off, routes are only recorded and nothing is drawn.
- GPX export for a ride, a day, or everything.

**Knocks**
- Trigger phone actions by changing the assist level on the bike in a set order, for example `545454`:
  media play/pause, next/previous track, volume, or call a number.
- Rules reject sequences that are too easy to trigger by accident or that conflict with each other.

**Background mode**
- Keeps the connection service running, restarts it after a reboot, and connects as soon as the display is switched on.
- Recording and knocks keep working with the screen off.

**Developer screen**
- Raw frame log, test writes for navigation fields, and a "Read display config" tool.

## Privacy

- Ride data, GPS points and settings are stored in the app's private storage only. The app has no accounts and no analytics.
- GPS recording is opt-in. Without the map switch, the app makes no network requests.
- With the map on, map tiles are downloaded from the provider of the chosen style (OpenStreetMap, OSM France for
  CyclOSM, or CARTO). That provider can see which area you look at, not your recorded route. Tiles are cached on the phone.
- Android's automatic cloud backup is disabled, so ride history does not end up in a Google backup. Export GPX files
  if you want to keep a route.

## Requirements

- Android 12 or newer (API 31).
- An EKD01-TB display (or a compatible BikeGo display; only the EKD01-TB is tested).
- Optional: GPS for route recording; internet for map tiles.

Permissions and why:

| Permission | Used for |
|---|---|
| Nearby devices (Bluetooth scan/connect) | Finding and talking to the display |
| Location (fine, and "all the time" for screen-off recording) | Optional GPS route recording |
| Foreground service (connected device, location) | Keeping the connection and recording alive |
| Physical activity | Background mode: waking the app when you start riding |
| Notifications | The small service notification (can stay hidden) |
| Phone (call) | The "call" knock action |
| Internet, network state | Map tiles only |
| Run at boot | Restarting background mode after a reboot |

## Build

1. Open the project in Android Studio and let Gradle sync.
2. Run the `app` configuration on a phone (a real phone is needed for Bluetooth).
3. Pair nothing in system settings: connect from the app's **Settings → Bikes** screen.

Stack: Kotlin, AppCompat views (no Compose), SQLite, Google Play services (activity recognition only), osmdroid for maps.
`compileSdk`/`targetSdk` 34, `minSdk` 31.

## Project layout

Package `com.char_noir.ekd01` in `app/src/main/java/com/char_noir/ekd01/`:

| File | What it does |
|---|---|
| `Protocol.kt` | Frame building and parsing, auth reply, telemetry decoding |
| `BikeLink.kt` | BLE scan, connect, reconnect, request queue, event log |
| `BikeService.kt`, `BackgroundMode.kt`, `BootReceiver.kt`, `ActivityTransitionReceiver.kt` | Background connection and its wake-ups |
| `BikeRecorder.kt`, `RideDb.kt`, `RideStats.kt` | Ride recording, SQLite storage, ride maths and charge detection |
| `GpsTracker.kt`, `TrackMap.kt`, `GpxExport.kt` | Optional GPS recording, map drawing, GPX export |
| `Knocks.kt`, `KnockActions.kt` | Knock rules, storage and the phone actions |
| `MainActivity.kt` | All screens (built in code from XML layouts), navigation |
| `LineChartView.kt`, `TimelineView.kt` | Small custom views for graphs and the day timeline |

The BLE protocol is documented in [PROTOCOL.md](PROTOCOL.md).

## Status

A working personal tool, tested on one display (EKD01-TB) and one phone. Not yet published. Known open items:
some display configuration bytes and reads are still undecoded (see `PROTOCOL.md`), and the stricter knock
"staircase" rule is not implemented.

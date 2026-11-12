# EKD01-TB (BikeGo) BLE protocol

Decoded from an HCI snoop capture plus the BikeGo APK (com.huiye.ebike v2.1.7, with 2.1.0 for the
navigation classes). Implemented in `app/src/main/java/com/char_noir/ekd01/Protocol.kt`.

## Transport

Nordic UART Service `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
- phone → display: write characteristic `6e400002-…` (handle 0x0102), Write Request
- display → phone: notify characteristic `6e400003-…` (handle 0x0104)
- The link is not bonded or encrypted. BLE data length is 27, so long frames arrive in 2 packets: reassemble.

## Frame

`55 AA | LEN | SRC | DST | CMD | ARG | DATA[LEN] | CRC16 (LE)`

CRC16 = `~sum(LEN … last DATA byte) & 0xFFFF`. All 280 frames in the capture verify.

| Field | Values |
|---|---|
| Addresses | `11` phone, `10` display/controller, `A5` settings, `F1` navigation / info block |
| CMD | `01` read, `02` write, `03` push (nav), `04` read response, `05` write ack (data `00`), `06` periodic telemetry, `20` auth |

## Connection sequence

1. Phone sends HELLO (`10`, CMD `01`, ARG `00`, data `04`). The display replies with a 4-byte challenge.
2. Phone sends AUTH (`10`, CMD `20`, ARG `00`) with the 16-byte reply below. The ack carries 1 status byte:
   `00` = accepted, non-zero = rejected.
3. Phone sends one time-sync write to `10` (CMD `02`, ARG `46`): the LOCAL time as an epoch (UTC + the phone's
   current UTC offset, DST included), 4-byte LE. The display shows this value as-is, so plain UTC makes it show UTC.
   Verified on the display: this single write is enough. BikeGo also writes ARG `3E` (UTC epoch) and ARG `42`
   (UTC offset in seconds); they turned out not to be needed.
4. The display then sends telemetry about once a second.

BikeGo also reads A5/18 (device name, e.g. `EKD01_TB_N22`), A5/88, A5/E0 and F1/01 at startup. Only F1/01 is
decoded (next section); the others are not needed by this app.

## Display configuration block (read F1/01, 26 bytes)

BikeGo reads this block once at connect and keeps it as `MeterConfig`. It is a set of feature flags (plus some
ranges) that BikeGo checks before it uses a function. The Developer screen has a "Read display config" button.

Request (confirmed live, the display answers): `55 AA 01 11 F1 01 01 1A E0 FE`, i.e. LEN 1, SRC `11`, DST `F1`,
CMD `01`, ARG `01`, DATA `1A` (= 26 bytes wanted). Reply: SRC `F1`, CMD `04`, ARG `01`, 26 data bytes.

EKD01-TB_N22 returned (same on two reads):

```
00 00 56 07 00 00 0A A8 89 8B 07 0A 14 64 00 2C 01 01 02 32 00 F4 01 01 09 3C
```

Feature bits, taken from BikeGo's `MeterConfig` getters (Dart assembly; the byte index goes through Smi tagging, so
treat positions as a best reading). Only `bleUnlock` and `pushNotify` are confirmed by behaviour, and both come out the
same under the alternative index reading.

| Byte.bit | Flag in BikeGo | This display | Used for |
|---|---|---|---|
| 1 | category (whole byte) | 0 | unknown |
| 3.0 | `hasLampSwitch` | 1 | light toggle (A5/A6) |
| 3.1 | `hasPushNotify` | 1 | A5/E1 |
| 3.2 | `hasDateTime` | 1 | time sync |
| 3.7 | `hasBleUnlock` | **0** | proximity lock/unlock (10/0E) |
| 4.0 | `hasUpgradeControls` | 0 | |
| 4.2 | `hasLocate` | 0 | GPS/SIM tracker unit |
| 4.4 | `syncUnbindFlag` | 0 | |
| 4.5 | `unlockFlag` | 0 | not understood (may relate to unlock) |
| 5.0 | `tirePressureFlag` | 0 | A8/00 tyre pressure |
| 6.3 | `hasUnit` | 1 | A5/E0 |
| 7.7 | `hasGear` | 1 | A5/A4 |
| 8.7 | `hasBrightness` | 1 | A5/A7 |
| 9.7 | `hasBleUnlockLevel` | 1 | A5/E2 |
| 11.7 | `hasErrorCode` | 0 | A5/78 |

BikeGo also reads min/max values (brightness, gear, BT unlock level) from this block; those bytes (10 to 13 look like
`07 0A 14 64`) are not decoded.

## Auth

```
challenge = HELLO response data (4 bytes, changes every connection)
block     = challenge + 12 zero bytes           # 16 bytes
key       = "2CTDU40qNyCgTjb1"                  # ASCII, hardcoded in the BikeGo app
reply     = AES_ECB_NoPadding_Encrypt(key, block)   # 16 bytes, sent as the AUTH frame data
```

Verified byte for byte against the capture: challenge `8f66acfd` → reply `c6dc6115b6ad2717a5098a312ff5f9a9`.
A replayed AUTH blob from another connection is rejected with status `01`, because it was computed for a different challenge.

The key is a static constant in the app, not fetched per device, so it is probably shared by all units.
That means no real security: anyone with it can control an EKD01-TB display over BLE while it is advertising.

## Settings (phone → `A5`, CMD `02`)

| ARG | Meaning | DATA | Status |
|---|---|---|---|
| `A4` | Assist level | `level 00`, 1..5 | confirmed |
| `A6` | Light | `01 00` on / `00 00` off | confirmed |
| `A7` | Screen brightness | 1 byte, 1..4 | confirmed |
| `E2` | BT unlock level | 1 byte, 1..5 | confirmed |
| `E0` | Units | 1 byte, 0 = km, 1 = mi | confirmed live |
| `B4` | Auto-off timeout | 2 bytes LE, minutes | confirmed live (1 gave a 1-minute timeout) |
| `E1` | Phone notification push on/off (BikeGo `switchMeterMsgNotification`) | 1 byte | display flag `hasPushNotify` is set, but it is only a switch: BikeGo calls it from an `AncsManager` (Apple ANCS, the display reads an iPhone's notifications itself). BikeGo has no code that sends notification text, so nothing to show on Android. Removed from the app |

Other registers (payload lengths come from the app's register table; see `SETTINGS_REGISTERS` in `Protocol.kt`):
- `A0 A2 A8 AA AC AE B0` are rejected by this firmware (write ack status `02`).
- `B6` (27 bytes) is accepted but corrupts the brightness setting on write (firmware buffer overflow). Do not write it.
- Other registers are untested.

## Writes to the display itself (phone → `10`, CMD `02`)

All frames BikeGo 2.1.7 sends, from its Dart code (`MeterManager`). It has no speed-limit or speed-unlock write at all.

| ARG | Meaning | DATA | Status |
|---|---|---|---|
| `0E` | Bike lock (`openLock` / `lock`) | 1 byte: 1 = unlock, 0 = lock | **not supported by this display**: `hasBleUnlock` is 0 in the config block and writing it did nothing. BikeGo only sends it from its RSSI-based proximity unlock when that flag is set. Removed from the app |
| `3E`, `42`, `46` | Time sync (BikeGo sends all three) | 4-byte LE epoch | `46` verified, the others are not needed |
| `F1`/`02` | Unbind the display from the phone (`unbindMeter`, DST `F1`) | 1 byte, 0 | from BikeGo code; do not use |

Reads BikeGo uses: `A5`/`18` display version, `A5`/`88` display name, `A5`/`48` serial number, `A5`/`78` error codes, `A5`/`E0` units, `A5`/`E2` BT unlock level, `A3`/`18` controller version, `F1`/`01` configuration block (26 bytes), `A8`/`00` tyre pressure. The `A6` reads (`1C`, `5C`, `72`..`74`, `88`, `9C`, `AA`..`AC`, `BC`, `BD`, `C4`, `E0`) are for an optional GPS/SIM tracker unit, not the display.

## Navigation (phone → `F1`, CMD `03`, ARG `00`)

Fire-and-forget pushes. Payload is `[seq, msgType, …]`. `seq` is a transaction counter: 1 on start, then it increments.

| msgType | Meaning | Payload after `[seq, msgType]` |
|---|---|---|
| 1 | Start nav mode (display switches to nav layout) | none |
| 2 | Route-progress update | 18 bytes, below |
| 3 | Stop nav mode | none |

Update payload, distances in meters, all little-endian:

| Bytes | Field |
|---|---|
| 3 | curStepRetainDistance (counts down as you approach the maneuver) |
| 1 | tmpIconType (icon of the current maneuver) |
| 3 | nextStepDistance |
| 1 | nextIconType |
| 3 | nextNextStepDistance |
| 1 | nextNextIconType |
| 4 | pathRetainDistance (total remaining route) |

Icon codes: 1 straight, 2 left, 3 right, 4 slight left, 5 slight right, 6 sharp left, 7 sharp right,
8 U-turn, 10 complete or none, 0 unknown.

Confirmed live on the display (2026-11-08): "right turn in 200 m" values rendered a right-turn arrow with "0.2 km",
a straight icon with "0.5 km" and a flag icon with "1.2 km". The nextNext fields have no visible slot on that screen state.

The `F1` register scheme (reg 1 = 26 bytes, reg 2 = 1 byte) turned out to be a dead end. Use the raw push above.
A second subsystem `A3`, found in the app's register table, never appears in captures; every register is read-only on this firmware.

## Telemetry (display → phone, CMD `06`, about 1 s each)

**ARG `01`, 21 data bytes**

| Byte | Field |
|---|---|
| 4 | light (non-zero = on) |
| 5 | assist level |
| 7 | battery % |
| 8 | speed unlock flag (non-zero = unlocked, speed above 25 km/h allowed), reported by the owner |
| 9–10 | current speed, LE (byte 9 low, byte 10 high), km/h × 10 (100 → 10.0 km/h) |
| 11–12 | trip, LE, × 0.01 km |
| 15–18 | odometer, LE, × 0.01 km |

Verified in the capture: 66 %, 40.43 km trip, 3723.37 km odometer.

**ARG `09`, 16 data bytes**

| Byte | Field |
|---|---|
| 0–1 | ride time, seconds (5669 = 1 h 34 m) |
| 4–5 | average speed, × 0.01 km/h (25.67) |
| 6–7 | max speed, × 0.1 km/h (46.7) |
| 12 | brightness (0x41..0x44 ≈ 1..4, probable) |
| 15 | BT unlock level |

## Open questions

- Current speed is bytes 9–10 (LE, × 0.1); a single byte capped the display at 25.5 km/h. Byte 8 is the unlock flag. Both are owner-reported and not yet checked against a capture above 25 km/h.
- The F1/01 block is only partly decoded (see its section); bytes 10 to 13 and the min/max values are open.
- `unlockFlag` (config byte 4, bit 5) is 0 on this display; unknown whether it relates to the speed-unlock flag in T1 byte 8.
- The startup reads A5/88 and A5/E0 are not decoded.

# EKD01-TB (BikeGo) BLE protocol

Decoded from an HCI snoop capture plus the BikeGo APK (com.huiye.ebike v2.1.7, with 2.1.0 for the
navigation classes). Implemented in `app/src/main/java/com/adilet/ekd01/Protocol.kt`.

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
3. Phone sends three time-sync writes to `10` (CMD `02`): ARG `3E` = local time as epoch (UTC epoch minus tz offset),
   ARG `42` = tz offset in seconds (3600), ARG `46` = UTC epoch. Each is a 4-byte LE value.
4. The display then sends telemetry about once a second.

BikeGo also reads A5/18 (device name, e.g. `EKD01_TB_N22`), A5/88, A5/E0 and F1/01 (26-byte settings block)
at startup. Those reads are not needed by this app and their contents are not decoded.

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

Other registers (payload lengths come from the app's register table; see `SETTINGS_REGISTERS` in `Protocol.kt`):
- `A0 A2 A8 AA AC AE B0` are rejected by this firmware (write ack status `02`).
- `B6` (27 bytes) is accepted but corrupts the brightness setting on write (firmware buffer overflow). Do not write it.
- Other registers are untested.

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
| 9 | current speed, km/h × 10 (100 → 10.0 km/h) |
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

- Byte 9 as current speed is what `Protocol.kt` decodes; the original capture had speed 0, so it is not verified against the capture.
- Contents of the startup reads (A5/88, A5/E0, F1/01 block) are not decoded.
- Whether the time-sync writes are required for any display feature is untested.

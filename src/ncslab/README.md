# eEBV / PSGX320 (Prophesee GenX320 on STM32)

320x320 Prophesee GenX320 CM2 module on an STM32U5 / STM32H745, from the NCS
lab (`download.ncslab.se`). Chip class: `ncslab.chip.EEBVGenX320`. Status:
Experimental.

Unlike the other live cameras this is not a libusb device. It appears as a
virtual serial port and jAER talks to it with
[jSerialComm](https://fazecast.github.io/jSerialComm/). Firmware V0.5
(STM32U599) is a TinyUSB CDC-ACM device, USB id `cafe:4001`: `/dev/ttyACM*` on
Linux, `COMx` on Windows.

## Using it

1. Plug the sensor in.
2. **Interface → Refresh**, then pick `eEBV GenX320 on <port>`. jAER switches
   the AEChip to `EEBVGenX320`.

A serial port is never opened automatically: opening sends text to the port,
so you choose it. Only ports with USB id `cafe:4001` are listed
(`EEBVHardwareInterfaceFactory.KNOWN_BRIDGES`). That is TinyUSB's example id,
so another TinyUSB board can show up too. `-Djaer.eebv.allPorts=true` lists
every USB serial port, e.g. for a sensor behind a UART bridge (`/dev/ttyUSB*`).

Linux: the user needs access to the port. Add yourself to the group that owns
it (`uucp` on Arch, `dialout` on Debian/Ubuntu; log out and in again), or for a
quick test `sudo chmod 666 /dev/ttyACM0` after plugging in. If no
`/dev/ttyACM*` appears, check that the `cdc_acm` kernel module can load.

The port is opened at 12 Mbps, 8N1, RTS/CTS. Linux `cdc_acm` rejects that
non-standard rate, so the driver falls back to 4 Mbps; the USB link ignores the
value. Overrides: `-Djaer.eebv.baud=N`, `-Djaer.eebv.rtscts=false`.

## Protocol

Text commands end in LF. jAER uses:

| Command | Meaning |
|---------|---------|
| `??` | help menu; sent on open as an identity check, reply is logged |
| `+` | start streaming events |
| `-` | stop streaming |

Biases are set with `!B<i>=<v>`. The Biases window (`EEBVConfig`) has
user-friendly sliders, the 12 raw biases, the firmware bias presets (`!BD<n>`),
hot-pixel masking (`!EMH`, `!EMC`), LED, and a free command box. Replies are
logged at FINE with the prefix `eEBV <port>:`.

If the eEBV is the only camera found it opens automatically; the driver
refuses a device whose `??` reply does not contain `PSGX320`. With other
cameras attached, choose it from the Interface menu.

Stream: at a word boundary, a byte with the top bit clear starts a text line
(to LF); a byte with the top bit set starts a 4-byte big-endian word.

```
bit 31      1
bits 30-19  timestamp in us, wraps every 4096 us
bit 18      polarity
bits 17-9   x [0..319]
bits 8-0    y [0..319]
```

x bits 17-15: `101` exception (bit 14 set: 00 timestamp overrun, 01 DCMI drop,
10 SPI/USB drop) or status (bit 14 clear, object tracking, ignored); `110` IMU
channel word (bits 14-12: GyrX, GyrY, GyrZ, temperature, AccX, AccY, AccZ, IMU
time; bits 11-0 value); `111` reserved. Decoder: `ncslab.serial.PsGx320Parser`.

## Timestamps

The 12-bit timestamp is unwrapped to monotonic microseconds from a session
origin (key `0` resets it). A quiet gap longer than 4.096 ms cannot be seen in
the timestamps alone. The parser adds a wrap for each repeated "timestamp
overrun" exception word, and when device time falls more than 50 ms behind
host time it adds whole wraps to catch up. Gaps between 4 ms and 50 ms with no
words at all can therefore read short until the next correction.

## Checked on hardware (firmware V0.5, 2026-10-02)

- Word layout, text/word framing, replies interleaved while streaming.
- Timestamp tick is 1 us and tracks host time; streaming starts about 0.65 s
  after `+`.
- Live view in AEViewer, pause/resume, close and reopen, AEDAT-4 record
  (17 M events in 26 s, about 650k events/s) and read-back.
- At high event rates the sensor sends "DCMI packet drop" exception words
  every few hundred microseconds: events are lost on the sensor board before
  the host. jAER counts them (`getDeviceDropCount`) and flags dropped data.

## Bias behaviour measured on the sensor (firmware V0.5)

Scene: indoor, static camera, about 125k events/s per polarity at defaults.

| Bias | Effect |
|------|--------|
| `diff_on` (default 30) | higher = fewer ON events: 10 → 1.4 M/s, 55 → 14k/s, 90 → 1.7k/s |
| `diff_off` (default 33) | higher = fewer OFF events: 10 → 1.3 M/s, 55 → 12k/s, 90 → 0.2k/s |
| `fo` (default 34) | higher = more bandwidth and events; 28 → 80k/s total, 22 → 16k/s, 16 → 1.6k/s, 10 and below → no events |
| `hpf` (default 0) | higher = fewer events: 60 → 140k/s total, 120 → 24k/s |
| `refr` (default 10) | weak, not monotonic: 0 → 150k/s total, 40–70 → 240k/s, 130 → 170k/s |

Biases are 8-bit: `!B4=300` reads back as 44, and `sm_pdy` defaults to 164.

## Not yet checked

- IMU. Firmware V0.5 sent no IMU words and has no IMU command. The decoder
  assumes 12-bit two's complement, ±2 g and ±250 deg/s full scale, temperature
  in deg C (`EEBVHardwareInterface`).
- Meaning of the "timestamp overrun" word and of its counter field (none seen).

## Firmware V0.5 command menu

```
 +/-                  - enable/disable sensor event streaming
 !E+/-                - enable/disable sensor power
 !EM[+/-]<y>,<x>      - mask enable(+) / disable(-) individual pixel y,x
 !EM[+/-]WYS,XS,YE,XE - mask enable(+) / disable(-) window (YS,XS) to (YE,XE)
 !EMWYS,XS,YE,XE      - set active window to (YS,XS) to (YE,XE)
 !EMT                 - transfer pixel mask to sensor (->activate pixel mask)
 !EMH                 - detect and mask hot pixel (and transfer), C clears current mask
 !EMC                 - clear pixel mask (and transfer)
 ?EM                  - print current pixel mask
 !EF[X,Y,B,N]         - flip axes X, Y, Both, None (in HW)
 !ES[+/-]             - swap X/Y addresses (in SW)
 !EF[+,-]             - send only first pixel event (discard consecutive events of same polarity)
 !EP[+,-,A]           - event polarity filter: positive/negative/all
 !ED=n                - event-index-based down-sampling: only send every n-th event
 !B<i>=<v>            - set bias i to value v
 !BD<n>               - load default bias set <n>, use ?BD for list
 ?B[i]                - retrieve bias i (use a for all)
 ?BN                  - show list of bias index / names
 !S+/-/=t             - set servo output pin on/off/t pulse [500...2500us]
 !L-/+/.[=t]          - LED off/on/blinking/alarm [in ms]
 R                    - reset board
 pRog                 - enter boot loader
 ?V                   - display firmware version
 ?C                   - display chip info
 ??                   - display this help
```

Biases (`?BN`), with the values read at power-up (`?B`):

| i | name | value | i | name | value |
|---|------|-------|---|------|-------|
| 0 | pr | 61 | 6 | diff_off | 33 |
| 1 | fo | 34 | 7 | inv | 57 |
| 2 | fes | 63 | 8 | refr | 10 |
| 3 | hpf | 0 | 9 | invp | 56 |
| 4 | diff_on | 30 | 10 | req_pu | 116 |
| 5 | diff | 51 | 11 | sm_pdy | 164 |

# eEBV / PSGX320 (Prophesee GenX320 on STM32)

320x320 Prophesee GenX320 CM2 module on an STM32U5 / STM32H745, from the NCS
lab (`download.ncslab.se`). Chip class: `ncslab.chip.EEBVGenX320`. Status:
Experimental.

Unlike the other live cameras this is not a libusb device. It appears as a
virtual serial port (`/dev/ttyUSB*`, `COMx`) and jAER talks to it with
[jSerialComm](https://fazecast.github.io/jSerialComm/).

## Using it

1. Plug the sensor in.
2. **Interface → Refresh**, then pick `eEBV GenX320 on <port>`. jAER switches
   the AEChip to `EEBVGenX320`.

A serial port is never opened automatically: opening sends text to the port,
so you choose it. Until the VID/PID of the sensor's USB serial bridge is
entered in `EEBVHardwareInterfaceFactory.KNOWN_BRIDGES`, every USB serial port
is listed.

Linux: the user needs access to the port. Add yourself to the `dialout` group
(log out and in again), or for a quick test `sudo chmod 666 /dev/ttyUSB0`
after plugging in.

The port is opened at 12 Mbps, 8N1, RTS/CTS. Overrides:
`-Djaer.eebv.baud=N`, `-Djaer.eebv.rtscts=false`.

## Protocol

Text commands end in LF. jAER uses:

| Command | Meaning |
|---------|---------|
| `??` | help menu; sent on open as an identity check, reply is logged |
| `+` | start streaming events |
| `-` | stop streaming |

Other commands from the quickstart guide (`?B` biases, `!L=+` LED, `!EMH`
hot-pixel detection, `!EM-<y>,<x>`, `!EMT`) can be sent with
`EEBVHardwareInterface.sendCommand` / `sendCommandForReply`; there is no
control panel yet. Replies are logged at INFO with the prefix `eEBV <port>:`.

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

## Not yet checked on hardware

- IMU value format and scale. Assumed 12-bit two's complement, ±2 g and
  ±250 deg/s full scale, temperature in deg C (`EEBVHardwareInterface`).
- Meaning of the "timestamp overrun" word and of its counter field.
- Image orientation (y is flipped so sensor row 0 is at the top) and polarity
  sense.

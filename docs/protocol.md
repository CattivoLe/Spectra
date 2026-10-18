# Spectra BLE protocol

The Cardputer runs a GATT server; the iPhone is the central. Everything is
little-endian. Version 7.

## Service

All UUIDs share one base; only the first group differs.

| Name | UUID | Properties |
|---|---|---|
| Service | `53500001-8F2D-4B6A-9C1E-737065637472` | — |
| Info | `53500002-8F2D-4B6A-9C1E-737065637472` | read |
| Control | `53500003-8F2D-4B6A-9C1E-737065637472` | write |
| Results | `53500004-8F2D-4B6A-9C1E-737065637472` | notify |
| Firmware | `53500005-8F2D-4B6A-9C1E-737065637472` | write |

The device advertises the service UUID and a name fixed in that firmware.
The Cardputer build advertises `M5Cardputer`. Other boards use their own
fixed name. Older firmware advertised `Spectra-XXXX` from the Bluetooth
address; centrals still accept that name.

## Info (read)

UTF-8 JSON, for example:

```json
{"proto":6,"fw":"0.8.0","chip":"ESP32-S3","bands":["2.4"],"interval":5,"ota":true,"bat":85,"chg":false}
```

`interval` is the current automatic scan interval in seconds, 0 when off.
The phone sets it, and the sensor keeps that value across disconnect and
reboot. While the phone is away, the same interval drives the patrol.
`name` is the firmware's fixed sensor name, such as `M5Cardputer`.
`known` is the SSIDs the sensor can rejoin, newest first: the phone's list
plus any not yet uploaded. Passwords are not included. A long list is cut
to what fits in this read. Both fields are omitted by firmware that predates
them.
`ota` is true when the Firmware characteristic is present. Firmware that
predates updates omits the field; treat that as false.
`bat` is the battery level (0–100); absent when the hardware has no battery
monitor. `chg` is true while the pack is charging. On the Cardputer the
charger status pin is not connected to the ESP32, so `chg` is true when a USB
host is attached. A power bank that only supplies 5 V does not announce itself.

Version 7 adds the Cap LoRa-1262. `lora` is true when the SX1262 answered.
`lat` and `lon` are degrees times 10⁷, present only while the cap's GNSS
has a fix less than 30 seconds old. `sats` is the satellite count from the
latest GGA sentence. All three are omitted when the cap is not fitted.

## Control (write)

First byte is the command.

| Byte | Command | Payload |
|---|---|---|
| `0x01` | Scan once | — |
| `0x02` | Set interval | `u8` seconds between scans, 0 stops |
| `0x03` | Ack saved | `u32` capture id. The sensor deletes that stored capture |
| `0x04` | Scan LoRa | — |
| `0x05` | Remember network | `u8` SSID length (1–32), SSID bytes, `u8` password length (0–64), password bytes |
| `0x06` | Forget network | `u8` SSID length (1–32), SSID bytes |
| `0x07` | Set central name | UTF-8 device name, the rest of the write, at most 31 bytes |
| `0x08` | Ack known | — |
| `0x09` | Reset known list | — |

`0x05` adds one network to the list the sensor shows. Passwords are included
so a known network can be joined from the picker. `0x09` clears that list;
the phone sends it, then one `0x05` per network, newest first. The phone
keeps every network the sensor has ever uploaded. The sensor shows the
newest 80 of that list and does not upload them back.

The sensor itself remembers at most 8 networks it joined and has not yet
handed over. On each connection it uploads those (`0x23`). `0x08` means the
phone stored that batch; the sensor then deletes it. A failed join is not
stored. `0x07` is the phone's own system name; the sensor shows it while the
link is up and clears it on disconnect. A central that does not send these
is unchanged. Firmware that does not know a command ignores it.

`0x04` listens across the EU868 and RU864 channels on a Cap LoRa-1262,
at spreading factors 7, 9 and 12. It is ignored
when the cap is not fitted. A scan that is already running is not restarted;
the request is ignored. Every control command is ignored while a firmware
update is in progress.

## Firmware (write)

One command per write, with response. The payload is an application image
(`firmware.bin` from `pio run`), not a merged flash image. The central should
set the scan interval to 0 before starting and leave the sensor alone until
it reboots.

The image CRC-32 is the ISO-HDLC value used by zlib (polynomial
`0xEDB88320`, initial value 0). The CRC-32 of the ASCII bytes `123456789`
is `0xCBF43926`.

| Byte | Command | Payload |
|---|---|---|
| `0x01` | Begin | `u32` image size, `u32` CRC-32 |
| `0x02` | Data | `u32` offset from the start of the image, then the bytes at that offset |
| `0x03` | Finish | — |
| `0x04` | Abort | — |

Begin is rejected while a scan is running. Data is accepted only when
`offset` is the number of bytes already stored; any other offset aborts the
update. A single data write is at most 500 image bytes, and the whole write
(5 byte header plus those bytes) must fit in one ATT payload. Finish checks
the CRC and the ESP image, selects the inactive OTA slot, and the device
reboots into it.

## Results (notify)

Every notification is one message; the first byte is its type. The central
must negotiate an ATT MTU of at least 64 (iOS always does).

### `0x10` Scan started

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type = `0x10` |
| 1 | 2 | scan id (`u16`, wraps) |

### `0x11` Access point

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type = `0x11` |
| 1 | 2 | scan id |
| 3 | 6 | BSSID |
| 9 | 1 | RSSI, dBm (`i8`) |
| 10 | 1 | primary channel |
| 11 | 1 | secondary channel: 0 none, 1 above, 2 below |
| 12 | 1 | auth mode (see table) |
| 13 | 1 | pairwise cipher (ESP-IDF `wifi_cipher_type_t`) |
| 14 | 1 | group cipher (ESP-IDF `wifi_cipher_type_t`) |
| 15 | 1 | PHY flags: bit0 11b, bit1 11g, bit2 11n, bit3 LR, bit4 11ax, bit5 WPS, bit6 FTM responder |
| 16 | 2 | country code, two ASCII letters, `00 00` when absent |
| 18 | 1 | SSID length *n* (0 for a hidden network) |
| 19 | *n* | SSID bytes (not guaranteed to be UTF-8) |

### `0x12` Scan finished

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type = `0x12` |
| 1 | 2 | scan id |
| 3 | 2 | number of access points sent |
| 5 | 2 | scan duration, ms |
| 7 | 2 | number of stations sent (v2+; absent in v1) |
| 9 | 2 | number of probe requests sent (v3; absent in v1–v2) |

### `0x13` Station (v2)

A client device (station) seen through promiscuous-mode frame capture.

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type = `0x13` |
| 1 | 2 | scan id |
| 3 | 6 | client MAC address |
| 9 | 6 | AP BSSID (all zeros when the client is only probing, not associated) |
| 15 | 1 | RSSI, dBm (`i8`) |
| 16 | 1 | flags: bit0 = associated (client is connected to the AP in BSSID) |

### `0x14` Probe request (v3)

A probe request frame captured in promiscuous mode. One entry per unique
MAC + SSID pair; broadcast probes have `ssid_len` = 0.

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type = `0x14` |
| 1 | 2 | scan id |
| 3 | 6 | client MAC address |
| 9 | 1 | RSSI, dBm (`i8`) |
| 10 | 1 | channel the probe was seen on |
| 11 | 1 | SSID length *n* (0 for broadcast probe) |
| 12 | *n* | SSID bytes (UTF-8) |

### `0x15` Deauth event (v3)

A deauthentication or disassociation frame captured in promiscuous mode.
Entries are deduplicated by (source, target, BSSID, reason); `count` says
how many frames matched. A high count or a broadcast target is a strong
indicator of a deauth attack.

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type = `0x15` |
| 1 | 2 | scan id |
| 3 | 6 | source MAC (who sent the frame) |
| 9 | 6 | target MAC (`FF:FF:FF:FF:FF:FF` = broadcast, everyone) |
| 15 | 6 | BSSID (which network) |
| 21 | 2 | reason code (`u16`, 802.11 Table 9-49) |
| 23 | 1 | RSSI, dBm (`i8`) |
| 24 | 1 | channel |
| 25 | 2 | count of frames seen with this signature |

### `0x16` SSID reveal (v4)

When the sensor finds a hidden AP (empty SSID in a beacon) and then sees the
real name in a probe response or an association request, it sends this message
so the app can unmask the network.

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type = `0x16` |
| 1 | 2 | scan id |
| 3 | 6 | BSSID of the hidden AP |
| 9 | 1 | RSSI of the revealing frame, dBm (`i8`) |
| 10 | 1 | SSID length *n* |
| 11 | *n* | SSID bytes (UTF-8) |

### `0x17` Traffic (v5)

Estimated traffic volume per AP, accumulated from data frames captured in
promiscuous mode during the sniffing window. Useful for spotting streaming
devices (cameras, microphones) that generate continuous traffic.

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type = `0x17` |
| 1 | 2 | scan id |
| 3 | 6 | BSSID |
| 9 | 4 | total bytes (`u32`, all data frames to/from this AP) |
| 13 | 2 | total packets (`u16`) |

### `0x18` Vendor IE (v6)

WPS device info extracted from vendor-specific Information Elements (tag 221)
in beacons and probe responses during the promiscuous-mode capture window.
Only APs that advertise WPS produce an entry.

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type = `0x18` |
| 1 | 2 | scan id |
| 3 | 6 | BSSID |
| 9 | 1 | device name length *n* |
| 10 | *n* | device name (UTF-8, from WPS attribute 0x1011) |
| 10+*n* | 1 | manufacturer length *m* |
| 11+*n* | *m* | manufacturer (UTF-8, from WPS attribute 0x1021) |

### `0x19` Battery (v6)

Battery level and charging state of the sensor. Sent once per scan cycle,
before `scanFinished`. Absent when the hardware has no battery monitor.

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type = `0x19` |
| 1 | 2 | scan id |
| 3 | 1 | battery level, 0–100 % |
| 4 | 1 | flags: bit 0 = charging |

### `0x1A` `0x1B` `0x1C` Stored capture

A capture the Cardputer kept in flash after the side button. `0x1A` is a
Wi-Fi network, `0x1B` a Bluetooth advertisement, `0x1C` a LAN host. The
sensor sends every stored row when a phone connects. The phone answers
with control `0x03` and the capture id once it has the whole capture
(`index + 1 == total`). Until that ack, the sensor sends the capture again
on the next connection.

`unix` is seconds since 1970, or 0 when the Cardputer clock has not been set.
The phone then stamps the capture with the time it arrived.

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type |
| 1 | 4 | capture id (`u32`) |
| 5 | 4 | unix time (`u32`) |
| 9 | 2 | index of this row (`u16`) |
| 11 | 2 | number of rows in the capture (`u16`) |
| 13 | | the row, below |

Wi-Fi row: BSSID (6), RSSI (`i8`), channel, auth mode, SSID length, SSID.
Bluetooth row: address (6), address type, RSSI (`i8`), name length, name
(at most 20 bytes). An empty name is an unnamed device.
LAN row: IPv4 (4 octets), name length, name (at most 24 bytes).

On the Cardputer the LAN scan only sees hosts advertising mDNS, and only
after the radio has joined that Wi-Fi. A power-on scan that has not joined
stores nothing.

### `0x1D` Saved position (v7)

Sent once, ahead of the rows of a capture, when the cap had a GNSS fix at
the moment the side button stored it. The phone keeps that coordinate with
the capture. It is not a row and does not change `total`, so it is not
answered with an ack by itself.

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type = `0x1D` |
| 1 | 4 | capture id (`u32`) |
| 5 | 4 | unix time (`u32`) |
| 9 | 4 | latitude, degrees × 10⁷ (`i32`) |
| 13 | 4 | longitude, degrees × 10⁷ (`i32`) |
| 17 | 1 | satellites |

### `0x1E` LoRa device (v7)

One transmitter heard while listening across the band. The same device may be sent
again when a stronger copy arrives. `id` is a LoRaWAN DevAddr, the first
four bytes of a join-request DevEUI, or a fingerprint of an unknown packet.

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type = `0x1E` |
| 1 | 2 | scan id |
| 3 | 4 | frequency, kHz (`u32`) |
| 7 | 1 | spreading factor, 7–12 |
| 8 | 2 | RSSI, dBm (`i16`) |
| 10 | 1 | SNR, dB (`i8`) |
| 11 | 1 | flags: bit0 LoRaWAN, bit1 private sync word, bit2 join request, bit3 downlink, bit4 confirmed |
| 12 | 4 | id (`u32`) |

Bits 3 (downlink) and 4 (confirmed) are set only for LoRaWAN data frames, from
the message type in the MAC header. They were added without a version bump:
firmware that predates them leaves them clear, and an app that does not know
them ignores them.

### `0x1F` LoRa scan (v7)

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type = `0x1F` |
| 1 | 2 | scan id |
| 3 | 1 | phase: 1 started, 2 finished |
| 4 | 2 | devices sent (`u16`, 0 when started) |
| 6 | 2 | duration, ms (`u16`, 0 when started) |

### `0x22` LoRa progress (v7)

Sent at the start of each dwell, the same line the Cardputer draws in the
status bar. Older apps ignore the type.

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type = `0x22` |
| 1 | 2 | scan id |
| 3 | 4 | frequency, kHz (`u32`) |
| 7 | 1 | spreading factor, 7–12 |
| 8 | 2 | step, 1-based (`u16`) |
| 10 | 2 | total steps (`u16`) |

### `0x23` Known network

One network the sensor joined and has not yet handed to the phone. Sent on
each connection while any are waiting, and again after a later successful
join. The phone stores the SSID and password, and when `index + 1 == total`
answers with control `0x08`. Until that ack the sensor sends the batch again
next time. Passwords are not repeated in Info.

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type = `0x23` |
| 1 | 1 | index of this network |
| 2 | 1 | number of networks in the batch |
| 3 | 1 | SSID length *n* (1–32) |
| 4 | *n* | SSID |
| 4+*n* | 1 | password length *m* (0–64) |
| 5+*n* | *m* | password |

### `0x20` Saved LoRa device (v7)

A row from a LoRa capture the side button stored. Same framing as
`0x1A`–`0x1C`, then the device:

| Offset | Size | Field |
|---|---|---|
| 13 | 4 | frequency, kHz (`u32`) |
| 17 | 1 | spreading factor |
| 18 | 2 | RSSI, dBm (`i16`) |
| 20 | 1 | SNR, dB (`i8`) |
| 21 | 1 | flags, as in `0x1E` |
| 22 | 4 | id (`u32`) |

### `0x21` GNSS fix (v7)

The cap's current fix, sent when a phone connects and about every five
seconds while the fix changes. `flags` bit 0 is clear when there is no fix;
the coordinate fields are then zero.

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type = `0x21` |
| 1 | 4 | latitude, degrees × 10⁷ (`i32`) |
| 5 | 4 | longitude, degrees × 10⁷ (`i32`) |
| 9 | 1 | satellites |
| 10 | 1 | flags: bit 0 = valid fix |

### `0x30` Update status

Sent when an update is accepted, fails, or finishes, and about every 16 KiB
while data is arriving.

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type = `0x30` |
| 1 | 1 | phase: 1 ready, 2 progress, 3 done, 4 failed |
| 2 | 4 | bytes accepted (`u32`) |
| 6 | 1 | error (see table) |

| Error | Meaning |
|---|---|
| 0 | none |
| 1 | busy (a scan is still running) |
| 2 | image does not fit the update slot |
| 3 | the update could not start |
| 4 | offset was not the next byte |
| 5 | flash write failed |
| 6 | CRC-32 did not match |
| 7 | the image could not be activated |
| 8 | aborted |
| 9 | image header was not an ESP32 application |

`done` means the new image is the boot slot. The device reboots shortly after.

### Auth modes

Values follow ESP-IDF `wifi_auth_mode_t`:

| Value | Mode |
|---|---|
| 0 | Open |
| 1 | WEP |
| 2 | WPA-PSK |
| 3 | WPA2-PSK |
| 4 | WPA/WPA2-PSK |
| 5 | WPA2-Enterprise |
| 6 | WPA3-PSK |
| 7 | WPA2/WPA3-PSK |
| 8 | WAPI-PSK |
| 9 | OWE |
| 10 | WPA3-Enterprise 192-bit |
| 11 | WPA3-PSK (extended key) |
| 12 | WPA3-PSK (extended key, mixed) |
| 13 | DPP |
| 14 | WPA3-Enterprise |
| 15 | WPA2/WPA3-Enterprise |

Unknown values are shown as "Other".

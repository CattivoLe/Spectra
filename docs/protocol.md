# Spectra BLE protocol

The Cardputer runs a GATT server; the iPhone is the central. Everything is
little-endian. Version 10.

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
The Cardputer build advertises `M5Cardputer`. The ESP32-C5 N4 build
advertises `ESP32-C5` and reports bands `2.4` and `5`. The WROOM-32 build
advertises `WROOM32` and reports `2.4`. Those two boards have no screen,
keyboard or battery reading, so `bat` is omitted. Older firmware advertised
`Spectra-XXXX` from the Bluetooth address; centrals still accept that name.

## Info (read)

UTF-8 JSON, for example:

```json
{"proto":6,"fw":"0.8.0","chip":"ESP32-S3","bands":["2.4"],"interval":5,"ota":true,"bat":85,"chg":false}
```

`interval` is the current automatic scan interval in seconds, 0 when off.
The phone sets it, and the sensor keeps that value across disconnect and
reboot. While the phone is away, the same interval drives the patrol.
`name` is the firmware's fixed sensor name, such as `M5Cardputer`,
`ESP32-C5` or `WROOM32`.
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

Version 9 adds `alerts`, the alert mode flags set with control `0x0C`, and
`watch`, the number of addresses on the sensor's watch list (`0x0D`).
Firmware 0.10.1 adds `outputs`, what the board can alert with, in the same
bits as `alerts` (bit0 sound, bit1 LED): `3` on the Cardputer, `2` on the
ESP32-C5 (its RGB LED), `0` on the WROOM-32. When it is absent the phone
offers every mode, as before.

Version 10 (firmware 0.11.0) adds client traffic (`0x25`) and its count in
`0x12`. Older apps ignore both: the record type is unknown to them and the
count is past the fields they read. An app that gets a `0x12` without the
count is talking to an older sensor and judges access points by `0x17`, as
before. Either way round, the app only says the versions differ.

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
| `0x0A` | Start raw capture | `u8` channel (1–14, or 32–177 on a sensor that reports band `5` and runs firmware 0.10.1+; 0 = keep current), `u8` flags (bit0 = filter to the BSSID), `6` BSSID |
| `0x0B` | Stop raw capture | — |
| `0x0C` | Set alerts (v9) | `u8` flags: bit0 = sound, bit1 = LED. 0 is silent |
| `0x0D` | Set watch list (v9) | `u8` count (0–32), then count × `6` address bytes, first octet first |

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

`0x0A` pins the radio to one channel in promiscuous mode and streams the
management and data frames it sees as `0x24` notifications. It is a short,
thinned sniff, not a full capture: BLE moves far less than the air does, so the
sensor keeps a small ring of frames and drops the oldest when the phone falls
behind, and each frame is truncated to 256 bytes on the wire (the true length
is still reported). Frames can be filtered to one BSSID, but the phone normally
captures everything on the channel. Automatic scans pause while a capture runs,
the same way a firmware update holds them off. `0x0B` stops the stream, which
also happens when the phone disconnects.

`0x0C` chooses how the sensor announces what a scan finds on its own. The
LED blinks red for a critical finding and yellow for a warning, ranked the
same way as the phone's threat banners (a client that streams, see `0x25`, is
announced once, when it is first sure, and again if it stops and comes back);
orange for an item tracker (a Find
My accessory away from its owner, a Samsung SmartTag or a Tile) heard in
three Bluetooth scans in a row; green for an open network; blue for a
watched Bluetooth device; purple for a watched Wi-Fi BSSID. Sound and LED
follow the flags whether or not a phone is linked. The sensor keeps the
flags across reboot; the default is sound and LED.

`0x0D` replaces the list of watched addresses: Wi-Fi BSSIDs and Bluetooth
device addresses, written in the order they are printed (`AA:BB:…` sends
`0xAA` first). The sensor keeps it across reboot and matches it against
its own Wi-Fi and Bluetooth scans. Phones that only know a Bluetooth device
by an opaque identifier cannot put it on this list.

After boot the automatic interval waits for a first Wi-Fi scan request,
from the keyboard (Enter on the Wi-Fi tab) or from `0x01` / `0x02`; from
then on the interval drives the patrol. A Bluetooth scan asked for on the
sensor's own keyboard adds Bluetooth to the patrol: the interval then
alternates a Wi-Fi scan and a Bluetooth scan.

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
| 11 | 2 | number of client traffic records (`0x25`) sent (v10; absent before) |

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
promiscuous mode. The numbers stand for **250 ms** of listening: firmware up
to 0.10.1 listens that long on every channel, firmware 0.11.0 listens longer
where access points are and scales what it counted back to 250 ms, rounding
up. A phone can therefore compare it with one fixed bar whatever the sensor.
It has no direction and no client; `0x25` has both.

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type = `0x17` |
| 1 | 2 | scan id |
| 3 | 6 | BSSID |
| 9 | 4 | bytes in 250 ms (`u32`, all data frames to/from this AP) |
| 13 | 2 | packets in 250 ms (`u16`) |

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

### `0x24` Raw frame (v8)

One Wi-Fi frame captured in promiscuous mode after a `0x0A` request. A frame
larger than the ATT payload is split across several notifications that share a
`seq`; the metadata is on fragment 0, and the rest carry only more frame bytes.
The phone reassembles by `seq`, in `frag index` order, until it has `orig len`
bytes (or runs out, when the frame was truncated to the 256-byte snap length).

Fragment 0 (and any single-fragment frame):

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type = `0x24` |
| 1 | 2 | seq (`u16`, wraps) |
| 3 | 1 | fragment index = 0 |
| 4 | 1 | fragment count (≥ 1) |
| 5 | 4 | timestamp, microseconds since boot (`u32`, wraps) |
| 9 | 1 | channel |
| 10 | 1 | RSSI, dBm (`i8`) |
| 11 | 2 | original frame length (`u16`) |
| 13 | | frame bytes for this fragment |

Fragment *k* > 0:

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type = `0x24` |
| 1 | 2 | seq |
| 3 | 1 | fragment index |
| 4 | 1 | fragment count |
| 5 | | frame bytes for this fragment |

The frame is a bare 802.11 MAC frame (no radiotap); the phone adds a radiotap
header carrying the channel and RSSI when it writes the `.pcap` (link type 127).
The timestamp is only good for spacing frames apart: the phone anchors the first
one to the wall-clock moment the capture began.

### `0x25` Client traffic (v10)

What one client sent (ToDS) and received (FromDS) while the sensor listened on
its channel, and for how long it really listened. Sent after the `0x17`
records, busiest sender first, at most 32 per scan. Only clients heard sending
data have a record.

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type = `0x25` |
| 1 | 2 | scan id |
| 3 | 6 | client MAC address |
| 9 | 6 | AP BSSID |
| 15 | 1 | channel the counts are from |
| 16 | 1 | RSSI of the client's strongest frame, dBm (`i8`) |
| 17 | 2 | time spent listening on that channel, ms (`u16`) |
| 19 | 4 | uplink bytes (`u32`, client → AP) |
| 23 | 2 | uplink frames (`u16`) |
| 25 | 4 | downlink bytes (`u32`, AP → client) |
| 29 | 2 | downlink frames (`u16`) |
| 31 | 1 | busy slots: how many 100 ms slices of the listening time had uplink data in them (a slice at the end may be shorter) |

Bytes are whole 802.11 frames with their header and FCS, retransmissions
included. Null and QoS Null frames (power saving) are not counted. Downlink is
counted from the client's first uplink frame on. Group-addressed frames and
four-address frames (mesh backhaul, repeaters) are left out.

A strong frame is also heard, in part, on the neighbouring channels. Where the
scan knows the AP's channel only that channel counts; for an AP the scan did
not hear, the channel with the highest uplink rate is the one reported. Counts
from different channels are never added up.

How long the sensor listens: 250 ms on every channel it sweeps, plus, where
the scan heard an access point, an equal share of 4 s (at most 1 s more per
channel). Where it heard none but a client keeps sending, 750 ms more, on at
most two channels per sweep: the access point can be out of earshot while the
camera is in the room. Channels with an access point that are not in the fixed
list (DFS channels on the ESP32-C5) are added to the sweep. A sweep takes up
to 4 s longer than before, 5.5 s with two such quiet channels, and a little
more for added ones. Longer listening also finds more deauthentication frames
and stations.

**The streaming rule.** A client looks like a camera or a microphone in one
sweep when all of these hold:

- uplink of at least 8000 bytes per second of listening time (64 kbit/s);
- uplink frames of at least 200 bytes on average: acknowledgements of a
  download are about 100, and the download itself may be in frames the sensor
  cannot decode;
- uplink bytes at least twice the downlink bytes: a call goes both ways;
- at least 60 % of the 100 ms slices busy.

Each sweep like that adds one to the client's score (at most 4), each other
sweep takes one off; at 3 the client is reported. The sensor's own alert and
both phone apps use the same rule and numbers (`firmware/src/Stream.h`). They
have **not been calibrated on a real camera**: they are derived from typical
bitrates. A microphone with a low-bitrate codec (small packets) stays below
them. A backup or a live broadcast from a phone looks the same as a camera.
An app judges access points by `0x17` only when the sensor sends no client
records, because a network is also busy when someone watches a film.

**What the sensor does not hear.** The counts are what it decoded, not what
was sent:

- *Aggregated frames are heard.* The promiscuous filter MGMT | DATA lets
  A-MPDU subframes through one by one: the driver checks an MPDU against
  DATA | DATA_MPDU and an A-MPDU subframe against DATA | DATA_AMPDU, so the
  DATA bit alone is enough. Checked in the driver library of the pinned IDF
  for ESP32, ESP32-S3 and ESP32-C5 (`wDev_SnifferRxAmpdu`); not measured on
  the air.
- *40 MHz frames* are not decoded: the radio is tuned to a 20 MHz channel. On
  2.4 GHz most cameras use 20 MHz; an access point advertising a secondary
  channel (`0x11`, offset 11) may carry some clients at 40 MHz.
- *802.11ax (HE) frames* are not decoded by the ESP32 and ESP32-S3. The
  ESP32-C5 decodes 20 MHz HE; uplink OFDMA, which the access point triggers,
  is not expected to be heard.
- *More than one spatial stream* (MIMO) is not decoded: the driver reports
  such frames with no contents. Cameras normally have one antenna; phones,
  laptops and access points have more.
- *5 GHz* only on the ESP32-C5, *6 GHz* on none. On 5 GHz it hears 20 MHz
  only, so most 80 MHz traffic passes it by.
- The radio is shared with Bluetooth while a phone is connected, and frames
  are dropped when the driver runs out of buffers. Both lower the counts.
- Distance: a client that the access point hears well may be too weak here.

So a missing record does not prove that a device is silent.

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

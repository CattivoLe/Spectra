# Spectra BLE protocol

The Cardputer runs a GATT server; the iPhone is the central. Everything is
little-endian. Version 3.

## Service

All UUIDs share one base; only the first group differs.

| Name | UUID | Properties |
|---|---|---|
| Service | `53500001-8F2D-4B6A-9C1E-737065637472` | — |
| Info | `53500002-8F2D-4B6A-9C1E-737065637472` | read |
| Control | `53500003-8F2D-4B6A-9C1E-737065637472` | write |
| Results | `53500004-8F2D-4B6A-9C1E-737065637472` | notify |

The device advertises the service UUID and the name `Spectra-XXXX`, where
`XXXX` are the last two bytes of its Bluetooth address in hex.

## Info (read)

UTF-8 JSON, for example:

```json
{"proto":2,"fw":"0.2.0","chip":"ESP32-S3","bands":["2.4"],"interval":5}
```

`interval` is the current automatic scan interval in seconds, 0 when off.

## Control (write)

First byte is the command.

| Byte | Command | Payload |
|---|---|---|
| `0x01` | Scan once | — |
| `0x02` | Set interval | `u8` seconds between scans, 0 stops |

A scan that is already running is not restarted; the request is ignored.

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

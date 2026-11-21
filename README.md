# Spectra

Spectra audits your own wireless surroundings: the Wi-Fi networks around you,
the devices on your local network, nearby Bluetooth devices and your phone's
internet connection. It runs on iPhone and Android, optionally with a small
ESP32 sensor that adds what a phone cannot do by itself, and it has an
on-device assistant (llama.cpp) that explains what it found. Nothing is sent
to a server: scans, history and the assistant all stay on the phone.

iOS does not let apps list nearby networks, so on iPhone the Wi-Fi list comes
from the sensor: an M5Stack Cardputer (ESP32-S3), an ESP32-C5 or an
ESP32-WROOM-32 scans and sends every access point it hears to the app over
Bluetooth LE. Android can also scan with the phone's own radio.

```
android/    Android app (Kotlin, Jetpack Compose) and the llama.cpp engine module
ios/        Xcode project for the iPhone app and its packet-tunnel extension
firmware/   PlatformIO project for the sensor (Cardputer, ESP32-C5, WROOM-32)
docs/       BLE protocol between the apps and the sensor
data/       Catalogs shared by both apps (MAC vendors, Bluetooth IDs, RDAP) and their build scripts
```

> **Use it on networks and devices you own or are allowed to test.** Spectra
> is a defensive tool. It listens; it does not attack. Some features (raw
> 802.11 frame capture to `.pcap`, decrypting your own phone's TLS traffic,
> port scans of the local network) can reveal other people's data if pointed
> at a network that is not yours. Capturing or inspecting traffic you are not
> authorised to see is illegal in many countries. See [SECURITY.md](SECURITY.md).

## Download

The Android APK (arm64 phones) and the sensor firmware for all three boards
are published on the [Releases](https://github.com/CattivoLe/Spectra/releases) page,
with checksums and build attestations. How they are built and how to check
them: [docs/releasing.md](docs/releasing.md).

## Quick start

| Part | Needs | Build |
|------|-------|-------|
| Android | Android Studio, JDK 17+, Android SDK 36, NDK 29 | `android/scripts/fetch-llama.sh`, then `cd android && ./gradlew :app:assembleDebug` |
| iOS | Xcode 26, iOS 18 device or simulator | `ios/Tools/build-llama.sh` once (~15 min), copy `ios/Config/Signing.local.xcconfig.example` to `Signing.local.xcconfig`, open `ios/Spectra.xcodeproj` |
| Sensor | [PlatformIO](https://platformio.org) | `cd firmware && pio run -t upload` |

`android/local.properties` (with `sdk.dir=...`) and
`ios/Config/Signing.local.xcconfig` are per-machine and git-ignored. The
assistant's language models are downloaded by the app on first use; they are
not part of this repository.

Tests: `./gradlew :app:testDebugUnitTest` in `android/`, the `Spectra` scheme
in Xcode, and `pio test -e native` in `firmware/`.

A fork has to use its own identifiers: `SPECTRA_BUNDLE_ID` in
`Signing.local.xcconfig`, plus the app group (`group.cattivole.Spectra` in
both `.entitlements` files and in `TrafficSniffer.swift` /
`PacketTunnelProvider.swift`) and the tunnel's bundle id in the Xcode project.
The packet tunnel needs the paid Network Extensions capability; everything
else runs with a free developer account.

## Firmware

```sh
cd firmware
pio run -t upload
```

Headless boards have no screen and no keyboard. The phone starts scans over
Bluetooth. Flash them with their own environment (4 MB flash, two app slots):

```sh
pio run -e esp32-c5-n4 -t upload          # ESP32-C5 N4, 2.4 and 5 GHz
pio run -e esp32-wroom32-ch340 -t upload  # ESP32-WROOM-32, CH340 USB serial
```

The C5 dev board uses its USB-Serial/JTAG port. The WROOM-32 board uses the
CH340 UART; if the upload never starts, set `upload_speed = 115200` for that
environment. These builds report no battery and no LoRa cap. Rebuild the
Android app after `pio run` and **Flash over USB** on the Sensor tab writes
the image that matches the chip: Cardputer, ESP32-C5 or WROOM-32. A blank
chip is already in the ROM bootloader. Later images can also arrive over
Bluetooth.

Or install through Launcher: copy `firmware/.pio/build/m5stack-cardputer-adv/firmware.bin`
(after `pio run`, also saved as `firmware/Spectra.bin`) to the SD card and
choose **SD** → file → **Install**. Use the app image, not a merged one with
bootloader and partitions, or it overwrites Launcher.

The Cardputer advertises as `Spectra-XXXX`. Its screen shows the link state
and the strongest networks. Fn and the horizontal arrows move between Wi-Fi,
LAN, BLE, LoRa and Join; the vertical arrows scroll the list. **Enter** scans.
It works on its own: scans are saved to flash with the side button and uploaded
the next time a phone connects. **Join** picks a network and shows the password
in the clear; nothing connects until Enter confirms it. The credentials are
remembered so the password comes back next time. A LAN scan walks the link
only while it is already up.

Firmware 0.6.0 and newer can also be installed from the Sensor tab: **Update
firmware**, then pick `firmware.bin` from `pio run` — the same app image
Launcher takes, not a merged one. The phone sends it over Bluetooth; the
Cardputer checks the image and reboots into it. A blank chip, or older
firmware, has to be flashed once before that works. On Android, **Flash over
USB** on the Sensor tab writes the bootloader, partition table and app from
the phone: a blank ESP32-S3 is already in the ROM bootloader, and a chip that
is running something else needs G0 held while reset is tapped. A computer
(`pio run -t upload`) or the SD-card launcher still work. The partition
table (`default_8MB.csv`) has two app slots, which is what makes the later
Bluetooth swap possible.

The ESP32-S3 hears 2.4 GHz only. An ESP32-C5 also hears 5 GHz. A WROOM-32
hears 2.4 GHz only.

A Cap LoRa-1262 on the Cardputer-Adv EXT header adds two more radios. Its
GNSS fix is kept with any scan the side button stores, so a walk without
the phone still has a position when the capture is uploaded. **LoRa** on
the device, or **Scan LoRa** in the app, listens across the EU868 and RU864
channels and lists the transmitters it hears. The cap is optional; without it the
LoRa list says `No LoRa cap` and the app hides the section. A crosshair in the
header means the cap's GNSS is present (green when it has a fix), and an
antenna means the LoRa radio answered.

## iOS app

Copy `ios/Config/Signing.local.xcconfig.example` to
`ios/Config/Signing.local.xcconfig`, set your team, open
`ios/Spectra.xcodeproj` and run. Requires iOS 18.

On the Sensor tab, **Find sensor** lists Cardputers in range; the app
reconnects to the last one by itself. The **Demo sensor** switch feeds made-up
networks through the same protocol, so the app can be worked on without the
hardware; in the simulator, which has no Bluetooth, it turns on by itself.

The Bluetooth tab listens from the iPhone itself, with CoreBluetooth. It
lists nearby Bluetooth Low Energy devices — name, signal, company, services
and the Apple messages they broadcast (AirPods, Find My, HomeKit). iOS does
not reveal the hardware address, and it does not hear classic Bluetooth.

## Android app

```sh
cd android
./gradlew :app:assembleDebug
```

Open `android/` in Android Studio. Requires Android 8 or newer. The package
id is `cattivole.spectra`.

If the matching `firmware/.pio/build/` directory is present, the Android build
packs `bootloader.bin`, `partitions.bin`, `boot_app0.bin` and `firmware.bin`
for the Cardputer, the ESP32-C5 and the WROOM-32. **Flash over USB** on the
Sensor tab asks the chip which it is and writes that image. The bootloader
sits at `0` on the S3, `0x2000` on the C5 and `0x1000` on the WROOM-32. The
same zip can be picked by hand: `flash.json` names the chip and lists each
file with its offset.

The phone radio is a second source of networks, which iOS does not allow.
It hears 5 GHz and 6 GHz (the Cardputer is 2.4 GHz only) and it is throttled
by the system to a few scans per two minutes. A BSSID the sensor still sees
keeps the sensor's record: clients, ciphers, the 40 MHz side, probes and
deauth frames are not in a phone scan. On Android 13 and newer the app asks
for nearby Wi-Fi and Bluetooth without turning those scans into location;
older releases still need fine location, and location services have to be on
or the scan comes back empty.

The Bluetooth tab shows the hardware address and whether it is public or
random. Classic inquiry is a separate switch: on many controllers it pauses
low-energy scanning until it finishes. The GATT link asks for a larger ATT
MTU itself (iOS does that automatically) and reconnects to the last sensor
by its address.

Saved scans keep the records themselves, so a network, host or Bluetooth
device opens the same card from History. The interface follows the system
language (English or Russian); strings live in
`android/app/src/main/resources/spectra/strings-ru.tsv`, keyed by the English text.

The LAN tab reads `/proc/net/arp`, so neighbour hardware addresses are
usually visible, and it walks the joined Wi-Fi or Ethernet subnet. A prefix
wider than /20 is limited to the local /24. There is no raw ICMP, so a host
counts as present when a TCP connect opens or is refused, or ARP already has it.

## Protocol

See [docs/protocol.md](docs/protocol.md). The app's `SensorProtocol` and the
firmware's `main.cpp` both implement it; the tests in `ios/SpectraTests`
check the app against byte layouts written out from the document.

## Security, contributing, licenses

- Reporting a vulnerability, and what Spectra is not for: [SECURITY.md](SECURITY.md).
- Patches and issues: [CONTRIBUTING.md](CONTRIBUTING.md).
- Third-party code, data and the downloadable models:
  [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
- License: [MIT](LICENSE). Third-party components keep their own licenses;
  a binary you build and ship has to carry their notices too.

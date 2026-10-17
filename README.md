# Spectra

A Wi-Fi scanner for iPhone. iOS does not let apps list nearby networks, so
Spectra uses an M5Stack Cardputer (ESP32-S3) as its radio: the Cardputer scans
and sends every access point it hears to the app over Bluetooth LE.

```
firmware/   PlatformIO project for the Cardputer
ios/        Xcode project for the iPhone app
docs/       BLE protocol between the two
```

## Firmware

```sh
cd firmware
pio run -t upload
```

Or install through Launcher: copy `firmware/.pio/build/m5stack-cardputer-adv/firmware.bin`
(after `pio run`, also saved as `firmware/Spectra.bin`) to the SD card and
choose **SD** → file → **Install**. Use the app image, not a merged one with
bootloader and partitions, or it overwrites Launcher.

The Cardputer advertises as `Spectra-XXXX`. Its screen shows the link state
and the strongest networks; **S** or **Enter** scans from the keyboard. It
works on its own: scans are saved to flash with the side button and uploaded
the next time a phone connects. **Tab** opens Wi-Fi setup — pick a network and
type the password — so the on-device LAN scan has a link to walk; the
credentials are remembered and reconnect on their own.

Firmware 0.6.0 and newer can also be installed from the Sensor tab: **Update
firmware**, then pick `firmware.bin` from `pio run` — the same app image
Launcher takes, not a merged one. The phone sends it over Bluetooth; the
Cardputer checks the image and reboots into it. Older firmware has to be
flashed once over USB or from the SD card before that works. The partition
table (`default_8MB.csv`) has two app slots, which is what makes the swap
possible.

The ESP32-S3 hears 2.4 GHz only.

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
wider than /24 is limited to the local /24. There is no raw ICMP, so a host
counts as present when a short TCP connect succeeds or ARP already has it.

## Protocol

See [docs/protocol.md](docs/protocol.md). The app's `SensorProtocol` and the
firmware's `main.cpp` both implement it; the tests in `ios/SpectraTests`
check the app against byte layouts written out from the document.

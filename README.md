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
and the strongest networks; **S** or **Enter** scans from the keyboard.

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

## Protocol

See [docs/protocol.md](docs/protocol.md). The app's `SensorProtocol` and the
firmware's `main.cpp` both implement it; the tests in `ios/SpectraTests`
check the app against byte layouts written out from the document.

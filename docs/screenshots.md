# Screenshots

The pictures in `docs/images/` come from the Android app in demo mode on an
emulator. Real scans would show other people's network names, MAC addresses
and devices, so the screenshots never use them.

Demo mode ([`DemoMode.kt`](../android/app/src/main/kotlin/cattivole/spectra/DemoMode.kt))
works only in a debuggable build. It turns on the built-in demo sensor
(made-up Wi‑Fi networks, clients, probes and deauth frames), lists made-up LAN
hosts and feeds made-up Bluetooth advertisements. The phone's own Wi‑Fi scan,
the LAN sweep and the Bluetooth radio stay off. The names are invented; MAC
addresses are a real vendor prefix, so the maker shows, plus an obvious
pattern; IP addresses are in `192.168.1.0/24`.

## Taking them again

1. Build and install a debug APK on an emulator (Pixel 8 profile, 1080×2400):

   ```sh
   cd android
   ./gradlew -Pabi=arm64-v8a :app:assembleDebug
   adb -s emulator-5554 install -r -g app/build/outputs/apk/debug/app-debug.apk
   ```

2. Clean up the status bar:

   ```sh
   adb -s emulator-5554 shell settings put global sysui_demo_allowed 1
   adb -s emulator-5554 shell am broadcast -a com.android.systemui.demo -e command enter
   adb -s emulator-5554 shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941
   adb -s emulator-5554 shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
   adb -s emulator-5554 shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e fully true
   adb -s emulator-5554 shell am broadcast -a com.android.systemui.demo -e command network -e mobile hide
   adb -s emulator-5554 shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
   ```

3. Start the app in demo mode:

   ```sh
   adb -s emulator-5554 shell am start -n cattivole.spectra/.MainActivity --ez demo true
   ```

4. Open each screen and capture it with
   `adb -s emulator-5554 exec-out screencap -p > shot.png`. Do not pull a
   list down to refresh it: in demo mode the scan never ends, so the
   refresh indicator stays on screen.
   - `screen-wifi.png`: Wi‑Fi tab, scrolled to the list of networks;
   - `screen-lan.png`: LAN tab;
   - `screen-lan-device.png`: LAN → Hikvision (`192.168.1.57`);
   - `screen-bluetooth.png`: Bluetooth tab.

5. Scale them to 540×1200 and save as 8-bit PNG (about 60 KB each).

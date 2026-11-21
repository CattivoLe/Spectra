# Third-party components

Spectra builds on the code, data and models listed here. Each keeps its own
license. Anyone who ships a binary (an APK, an IPA, a firmware image) has to
pass on the notices that the right-hand column asks for, usually by including
the license text in the app's "About" or "Licenses" screen.

"In repo" means the source is committed here. "Fetched" means a build script
or a package manager downloads it. "Downloaded by the app" means the user's
phone fetches it at run time; it is never part of this repository or of a
release binary.

## Code

### Shared by both apps

| Component | Version | How it arrives | License | What it asks for |
|-----------|---------|----------------|---------|------------------|
| [llama.cpp / ggml](https://github.com/ggml-org/llama.cpp) | tag `b12359` | Fetched: `android/scripts/fetch-llama.sh`, `ios/Tools/build-llama.sh` | MIT | Copyright and license text in binary distributions |

### iOS

| Component | Version | How it arrives | License | What it asks for |
|-----------|---------|----------------|---------|------------------|
| [GRDB.swift](https://github.com/groue/GRDB.swift) | 7.12.0 | In repo: `ios/Packages/GRDBSQLCipher` (unmodified sources, own `Package.swift`) | MIT | Keep `ios/Packages/GRDBSQLCipher/LICENSE`; notice in binaries |
| [SQLCipher.swift](https://github.com/sqlcipher/SQLCipher.swift) (SQLCipher Community Edition + SQLite) | 4.20.0 | SwiftPM | BSD-3-Clause style (Zetetic LLC); SQLite is public domain | Copyright and disclaimer in binary distributions; no use of the Zetetic name for endorsement |
| [SwiftSoup](https://github.com/scinfu/SwiftSoup) | 2.14.3 | SwiftPM | MIT | Notice in binaries |
| [swift-jinja](https://github.com/huggingface/swift-jinja) | 3.2.0 | SwiftPM | Apache-2.0 | License text; NOTICE if present |
| [SwiftNIO](https://github.com/apple/swift-nio) | 2.104.0 | SwiftPM | Apache-2.0 | License text and its `NOTICE.txt` |
| [SwiftNIO SSL](https://github.com/apple/swift-nio-ssl) (bundles BoringSSL) | 2.37.5 | SwiftPM | Apache-2.0; BoringSSL parts under OpenSSL/ISC/MIT-style terms | License text, `NOTICE.txt`, BoringSSL notices |
| [swift-atomics](https://github.com/apple/swift-atomics), [swift-collections](https://github.com/apple/swift-collections), [swift-system](https://github.com/apple/swift-system) | 1.3.1, 1.7.3, 1.8.1 | SwiftPM | Apache-2.0 | License text |

### Android

| Component | Version | How it arrives | License | What it asks for |
|-----------|---------|----------------|---------|------------------|
| AndroidX (Compose, Material 3, Material Icons Extended, Activity, Lifecycle, Navigation, Core, SQLite) | see `android/*/build.gradle.kts` | Gradle | Apache-2.0 | License text |
| [Kotlin](https://kotlinlang.org), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 2.3.21, 1.9–1.10 | Gradle | Apache-2.0 | License text |
| [OkHttp](https://square.github.io/okhttp/) | 4.12.0 | Gradle | Apache-2.0 | License text |
| [osmdroid](https://github.com/osmdroid/osmdroid) | 6.1.20 | Gradle | Apache-2.0 | License text |
| [sqlcipher-android](https://github.com/sqlcipher/sqlcipher-android) | 4.6.1 | Gradle | SQLCipher Community Edition (BSD-3-Clause style) | Copyright and disclaimer in binaries |
| [jsoup](https://jsoup.org) | 1.15.1 | Gradle | MIT | Notice in binaries |
| [JSON-java](https://github.com/stleary/JSON-java) (`org.json`) | 20240303 | Gradle | Public domain | — |
| [Brotli decoder](https://github.com/google/brotli) (`org.brotli:dec`) | 0.1.2 | Gradle | MIT | Notice in binaries |
| Google code scanner (`play-services-code-scanner`) | 16.1.0 | Gradle | **Proprietary**: Google APIs / [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms) | Not open source. Builds that must be fully free (F-Droid, strict GPL) need it removed or swapped |
| Gradle wrapper | — | In repo: `android/gradle/wrapper/` | Apache-2.0 | — |

### Sensor firmware

| Component | Version | How it arrives | License | What it asks for |
|-----------|---------|----------------|---------|------------------|
| [Arduino core for ESP32](https://github.com/espressif/arduino-esp32) (via [pioarduino](https://github.com/pioarduino/platform-espressif32)) | platform 55.03.37 | PlatformIO | LGPL-2.1-or-later | Firmware binaries link it statically: whoever ships a binary must let users relink, which publishing the full source with build instructions satisfies |
| ESP-IDF and Espressif Wi-Fi/Bluetooth libraries | bundled with the platform | PlatformIO | Apache-2.0; the radio libraries are Espressif binary blobs under Espressif's own redistribution terms | License text |
| [M5Cardputer](https://github.com/m5stack/M5Cardputer), [M5Unified](https://github.com/m5stack/M5Unified), [M5GFX](https://github.com/m5stack/M5GFX) | git `HEAD` (unpinned) | PlatformIO | MIT | Notice in binaries |
| [IRremote](https://github.com/Arduino-IRremote/Arduino-IRremote) (pulled in by M5Cardputer) | — | PlatformIO | MIT | Notice in binaries |
| [NimBLE-Arduino](https://github.com/h2zero/NimBLE-Arduino) | ^2.3.0 | PlatformIO | Apache-2.0 | License text |
| [RadioLib](https://github.com/jgromes/RadioLib) | 7.8.1 | PlatformIO | MIT | Notice in binaries |
| [Unity](https://github.com/ThrowTheSwitch/Unity) (native tests only) | — | PlatformIO | MIT | — |

The Android build copies the sensor images from `firmware/.pio/build/` into
the APK when they are present, so an APK built that way also carries the
firmware's components above.

## Data

| File | Source | Terms | Notes |
|------|--------|-------|-------|
| `data/spectra/oui.tsv` | IEEE Registration Authority public listings (MA-L, MA-M, MA-S: `oui.csv`, `mam.csv`, `oui36.csv`), normalised by `data/tools/build_oui.py` | Published freely by the IEEE for lookup; no explicit license | Credit the IEEE RA as the source |
| `data/spectra/ble-companies.tsv`, `ble-services.tsv`, `ble-characteristics.tsv` | Bluetooth SIG Assigned Numbers | Bluetooth SIG terms for the Assigned Numbers documents | Credit the Bluetooth SIG; check the current terms before release |
| `data/spectra/rdap.tsv` | [IANA RDAP bootstrap](https://data.iana.org/rdap/dns.json), built by `data/tools/build_rdap.py` | IANA registry data, freely usable | — |
| Map tiles (Android history and trace maps) | [OpenStreetMap](https://www.openstreetmap.org) standard tile layer, fetched at run time | Map data ODbL; tiles under the [OSM tile usage policy](https://operations.osmfoundation.org/policies/tiles/) | Show "© OpenStreetMap contributors"; identify the app in the User-Agent; heavy use needs another tile provider |

## Language models (downloaded by the app)

The assistant's catalog (`ios/Spectra/Assistant/AssistantModels.json`, used by
both apps) points at GGUF files on Hugging Face. They are downloaded on the
user's request and are not part of this repository or any release binary.
Each model has its own license, which the user accepts by downloading it:

| Model | License |
|-------|---------|
| Ministral 3 3B Instruct (the chat model) | Apache-2.0 |
| EmbeddingGemma 300M (ranks web search results) | Gemma Terms of Use |

## Artwork

App icons are original (`ios/Tools/make_icon.swift`). In-app icons come from
Material Symbols / SF Symbols through the platform toolkits. GRDB's
documentation images in `ios/Packages/GRDBSQLCipher/GRDB/Documentation.docc`
are part of GRDB (MIT).

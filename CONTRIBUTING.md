# Contributing

Thanks for looking. Spectra is a personal project, so the bar for merging is
"it fits the app and it is easy to keep working", not "it is a good idea".
Opening an issue before a large change saves both of us time.

## Ground rules

- **Defensive features only.** Pull requests that add frame injection,
  deauthentication, jamming, credential capture aimed at other people's
  networks, or similar offensive capabilities will be closed. See
  [SECURITY.md](SECURITY.md).
- **No personal data in the repository.** Test fixtures use made-up SSIDs,
  documentation addresses (`192.0.2.0/24`, `198.51.100.0/24`,
  `203.0.113.0/24`) and MAC addresses with a vendor prefix plus an obvious
  pattern (`AA:BB:CC:00:00:01`). Never commit real captures (`.pcap`),
  screenshots of real networks, `local.properties`, signing files or models.
- **One change per pull request**, with tests where the code has them.
- Keep the apps in step: a protocol change touches `docs/protocol.md`, the
  firmware, the iOS and the Android `SensorProtocol` together.

## Building and testing

See the Quick start in [README.md](README.md). Before sending a change:

```sh
cd android && ./gradlew :app:testDebugUnitTest
```

```sh
cd firmware && pio test -e native
```

and run the `Spectra` scheme's tests in Xcode for iOS changes.

## Style

- Follow the code around you. Kotlin and Swift use the platform's standard
  formatting; firmware is Arduino-flavoured C++.
- User-facing strings are in English in code. Android's Russian translations
  live in `android/app/src/main/resources/spectra/strings-ru.tsv`, keyed by
  the English text; iOS uses `Localizable.xcstrings`.
- Commit messages: a short summary line, then what changed and why.

## License of contributions

Spectra is released under the [MIT License](LICENSE). By sending a pull
request you agree that your contribution is licensed under the same terms.
Do not submit code you cannot license that way (for example, code copied
from a GPL project).

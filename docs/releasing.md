# Releasing

Releases are built on GitHub Actions and published as
[GitHub releases](https://github.com/CattivoLe/Spectra/releases). The iOS app
is not part of them: it ships through the App Store as Probe Lab, and an iOS
build cannot be handed out any other way.

## What runs when

| Workflow | Trigger | What it does |
|----------|---------|--------------|
| [`ci.yml`](../.github/workflows/ci.yml) | pull request, push to `main` (not for `*.md`/`docs/` only changes) | firmware host tests, firmware for the three boards, Android unit tests, an arm64 debug APK |
| [`release.yml`](../.github/workflows/release.yml) | tag `vX.Y.Z` | the same with a release APK; signs it and publishes the release |
| `release.yml` | run by hand (Actions → Release → Run workflow) | dry run: builds everything and keeps it as the run's artifacts, publishes nothing |

Both use [`build.yml`](../.github/workflows/build.yml). The firmware jobs run
first, one per board (`m5stack-cardputer-adv`, `esp32-c5-n4`,
`esp32-wroom32-ch340`), and hand their images to the Android job, which packs
them into the APK as flashing bundles (`packFlashBundle` in
`android/app/build.gradle.kts`) and fails if one is missing. The APK is built
with `-Pabi=arm64-v8a`: phones only, half the size and half the native build
time of the universal debug build. PlatformIO, the Gradle cache and the
llama.cpp sources are cached; a run takes about 20–25 minutes cold and
10–15 minutes warm.

A release contains:

| File | What it is |
|------|------------|
| `Spectra-X.Y.Z.apk` | the Android app, signed with the release key |
| `spectra-flash-<chip>-F.zip` | firmware `F` for a blank board: bootloader, partition table, OTA data, app and `flash.json` with the offsets. `esp32s3` is the Cardputer ADV, `esp32c5` the ESP32-C5, `esp32` the WROOM-32. The app flashes the same zips over USB |
| `spectra-firmware-<chip>-F.bin` | the firmware app image alone, as an update from the app takes it |
| `THIRD_PARTY_LICENSES.md` | the components and their licenses (the apps show the full texts) |
| `SHA256SUMS.txt` | checksums of all the above |

Every APK, zip and bin also gets a
[build provenance attestation](https://docs.github.com/actions/security-for-github-actions/using-artifact-attestations),
so anyone can check that it was built by this repository's workflow.

## Versions

- **App:** `versionName` in `android/app/build.gradle.kts`. The tag must be
  `v` + `versionName`, or the release workflow stops. `versionCode` is derived
  from it (`1.2.3` → `10203`), so there is nothing else to bump.
- **Sensor firmware:** `kFirmwareTag` in `firmware/src/main.cpp`
  (`"SPECTRA-FW:x.y.z"`). It has its own version because the app compares it
  with the sensor's to offer an update: raise it only when the firmware
  changed. The release notes and file names carry it.

## One-time setup: the signing key

Until this is done, a tag builds everything but publishes nothing: the run
ends with a warning and keeps the unsigned APK and the firmware as artifacts.

1. Create the key on your own machine. Keep it and a backup **outside the
   repository**: an APK signed with another key cannot update an installed
   one, so losing it means every user has to uninstall and reinstall.

   ```sh
   keytool -genkeypair -v -keystore spectra-release.jks -alias spectra \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Add four repository secrets, either in Settings → Secrets and variables →
   Actions → New repository secret, or with `gh` (it asks for the value, so
   passwords stay out of the shell history):

   | Secret | Value |
   |--------|-------|
   | `SPECTRA_KEYSTORE_BASE64` | the keystore as base64 |
   | `SPECTRA_KEYSTORE_PASSWORD` | keystore password |
   | `SPECTRA_KEY_ALIAS` | `spectra` |
   | `SPECTRA_KEY_PASSWORD` | key password: the same as the keystore's (a PKCS12 keystore, keytool's default, has one password) |

   ```sh
   base64 -i spectra-release.jks | gh secret set SPECTRA_KEYSTORE_BASE64 --repo CattivoLe/Spectra
   gh secret set SPECTRA_KEYSTORE_PASSWORD --repo CattivoLe/Spectra
   gh secret set SPECTRA_KEY_ALIAS --repo CattivoLe/Spectra
   gh secret set SPECTRA_KEY_PASSWORD --repo CattivoLe/Spectra
   ```

3. Put the certificate's SHA-256 fingerprint in the README so people can check
   the APK (done for the current key). Each release's notes repeat it.

   ```sh
   keytool -list -v -keystore spectra-release.jks -alias spectra | grep SHA256
   ```

Secrets are not passed to workflows started by pull requests from forks, and
CI never asks for them: only `release.yml` signs.

## Each release

1. Raise `versionName` in `android/app/build.gradle.kts`. If the firmware
   changed, raise `kFirmwareTag` in `firmware/src/main.cpp` too.
2. In `CHANGELOG.md`, turn `## X.Y.Z (unreleased)` into `## X.Y.Z (YYYY-MM-DD)`
   and list the changes. That section becomes the release text, with the
   firmware version, checksums and signing certificate added below it. With
   no section for the version, the notes list the commits since the previous
   tag.
3. Optionally do a dry run: Actions → Release → Run workflow on `main`, then
   look at the artifacts.
4. Commit, tag and push:

   ```sh
   git tag v1.0.0
   git push origin main v1.0.0
   ```

The workflow checks the tag against `versionName`, runs the tests, builds and
signs, checks that the APK has all three firmware bundles and no x86 code,
verifies the signature and publishes. If a run fails after the tag is pushed,
fix it, delete the tag (`git push --delete origin v1.0.0 && git tag -d v1.0.0`)
and tag again.

## Local builds

- Release APK, unsigned unless a key is configured:

  ```sh
  cd android
  ./gradlew -Pabi=arm64-v8a :app:assembleRelease
  ```

  To sign locally, create an untracked `android/keystore.properties`:

  ```properties
  storeFile=/path/to/spectra-release.jks
  storePassword=...
  keyAlias=spectra
  keyPassword=...
  ```

  Environment variables `SPECTRA_KEYSTORE_FILE`, `SPECTRA_KEYSTORE_PASSWORD`,
  `SPECTRA_KEY_ALIAS` and `SPECTRA_KEY_PASSWORD` override it; CI uses those.
- Firmware: `pio run -e <env>` in `firmware/` before the Gradle build, or the
  APK carries no flashing bundle for that board.

## Checking a download

```sh
sha256sum -c SHA256SUMS.txt --ignore-missing
gh attestation verify Spectra-1.0.0.apk --repo CattivoLe/Spectra
apksigner verify --print-certs Spectra-1.0.0.apk   # compare with the fingerprint in the README
```

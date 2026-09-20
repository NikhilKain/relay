# Relay

Your devices, together. Relay moves files, text, links and the clipboard between your
phone and your computers over your own Wi-Fi — no account, no cloud, no servers in the
middle.

- **Universal clipboard.** Copy on one device, paste on another: text, images, whole files.
- **Send anything.** Files, folders, photos, notes and links, from the app or the share sheet.
- **Drag and drop to your phone.** On the desktop, drag a file to the shelf at the edge of
  the screen and drop it on a device.
- **Ecosystem mode.** Devices you have paired act as one: what they send arrives straight
  away, with no Accept prompt, ready to paste.
- **Home-screen widget.** Your devices, one tap from sending.
- **Tap to share contacts.** Hold two Android phones back to back.

Everything travels directly between devices over TLS 1.3, and pairing is confirmed by
comparing shapes and a number on both screens.

## Getting Relay

| Device | Where |
| --- | --- |
| Android | Google Play |
| Windows, Linux | [Releases](../../releases) |
| iPhone, iPad, Mac | In the works |

On Windows, unzip the release and run `Relay.exe`; it lives in the system tray. Windows
may warn about an unrecognised app, because the builds are not yet code-signed.

## Building from source

Requires JDK 17+ and the Android SDK (compileSdk 37).

```bash
./gradlew :app:assembleDebug          # Android
./gradlew :desktop:run                # desktop app
./gradlew test :app:testDebugUnitTest # the test suite
./gradlew :desktop:createDistributable -Prelay.packagingJdk=/path/to/jdk17
```

Release builds are signed only when `keystore.properties` is present; without it the
release build is produced unsigned, so anyone can build Relay for themselves.

## How it is put together

| Module | What lives there |
| --- | --- |
| `core:protocol` | The Relay protocol: messages, framing, versions |
| `core:security` | Identities, certificates, pairing codes |
| `core:discovery` | Finding devices on the local network |
| `core:transfer` | The transfer engine: chunks, resume, checksums |
| `core:node` | The engine that ties those together |
| `core:designsystem` | Relay's Material 3 Expressive design system (Android + desktop) |
| `core:data` | Android storage: database, settings, files |
| `app` | The Android app |
| `desktop` | The Windows, Linux and macOS app |

The core modules are plain JVM code with no Android or UI dependencies, and are covered by
the test suite.

## Privacy

Relay has no account and no server. What you send goes straight from one of your devices
to another. Optional, anonymous usage statistics are off unless you turn them on in
Settings. See [docs/PRIVACY.md](docs/PRIVACY.md).

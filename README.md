<p align="center">
  <img src="site/images/icon.png" alt="Relay" width="96">
</p>

<h1 align="center">Relay</h1>

<p align="center">Your devices, together. Files, clipboard and links between your phone and your computer, over your own Wi-Fi. No account, no cloud.</p>

## Download

| Device | Where |
| --- | --- |
| Android 10+ | [Google Play](https://play.google.com/store/apps/details?id=com.vythera.relay) |
| Windows 10/11 | [Releases](../../releases/latest) — `Relay-1.0.2.exe` installer, or the portable zip |
| macOS 12+, Apple Silicon | [Releases](../../releases/latest) — `.dmg` |
| Linux | [Releases](../../releases/latest) — `.deb` and `.rpm` |
| iPhone and iPad | In the works |

## Installing on a computer

The desktop builds are **not code-signed**, so your computer will say the publisher is
unknown.

### Windows

Use the **installer** (`Relay-1.0.2.exe`). Run it; at **Windows protected your PC**,
choose **More info**, then **Run anyway**. Relay then starts with your computer if you
turn that on in Settings, and lives in the system tray.

Prefer the portable zip? **Unpack it first** to a permanent folder, then run `Relay.exe`
from there. Running it straight from inside the zip makes Windows unpack it into a
temporary folder that it cleans away later.

If Windows says **Smart App Control blocked this app**, that feature only allows signed
apps. It can be turned off in Windows Security, under *App & browser control*.

On first launch, allow Relay through Windows Firewall on **Private networks**, otherwise
your phone cannot reach the computer.

### macOS

Open the `.dmg` and drag **Relay** into Applications. macOS will probably say *"Relay is
damaged and can't be opened."* Nothing is damaged; that is how macOS describes an app
that is not notarised. Clear it once:

```bash
xattr -dr com.apple.quarantine /Applications/Relay.app
```

On macOS 15 and later, allow Relay to find devices on the local network when asked.

### Linux

```bash
sudo dpkg -i relay_1.0.2-1_amd64.deb      # Debian, Ubuntu, Mint, Pop!_OS
sudo rpm -i relay-1.0.2-1.x86_64.rpm      # Fedora, openSUSE, RHEL
```

## Connecting two devices

1. Install Relay on both.
2. Put them on the same Wi-Fi, or connect one to the other's hotspot.
3. Pick the other device, check that both screens show the same three shapes, and
   confirm. They find each other by themselves from then on.

## Privacy

Relay sends everything directly between your devices, encrypted with TLS. Nothing goes
through a server. See the [privacy policy](https://nikhilkain.github.io/relay-site/privacy.html).

## Building the desktop app

This repository holds the source of Relay for Windows, Linux and macOS, and the engine it
shares with the Android app. The Android app itself is published on Google Play only and
is not part of this repository.

Requires JDK 17 or later. The shared design library also targets Android, so Gradle needs
an Android SDK to configure the build (`ANDROID_HOME`); nothing Android is built.

```bash
./gradlew :desktop:run                  # run the app
./gradlew :core:node:test               # the engine's tests
./gradlew :desktop:createDistributable  # a portable app
./gradlew :desktop:packageExe           # Windows installer (needs WiX 3)
./gradlew :desktop:packageDeb           # .deb; packageRpm for .rpm (Linux only)
./gradlew :desktop:packageDmg           # .dmg (macOS only)
```

| Module | What lives there |
| --- | --- |
| `core:protocol` | The Relay protocol: messages, framing, versions |
| `core:security` | Identities, certificates, pairing codes |
| `core:discovery` | Finding devices on the local network |
| `core:transfer` | The transfer engine: chunks, resume, checksums |
| `core:node` | The engine that ties those together, and the device tools |
| `core:designsystem` | Relay's Material 3 Expressive design system |
| `desktop` | The Windows, Linux and macOS app |

Builds made from source offer no usage statistics; the analytics key is not part of the
source.

## Licence

Apache License 2.0. See [LICENSE](LICENSE).

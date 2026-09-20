<p align="center">
  <img src="docs/images/banner.png" alt="Relay" width="820">
</p>

<p align="center">
  Files, clipboard and links between your phone and your computer.<br>
  Over your own Wi-Fi. No account, no cloud, nothing in the middle.
</p>

---

## What Relay does

- **Universal clipboard.** Copy on one device, paste on another: text, images, whole files.
- **Send anything.** Files, folders, photos, notes and links, from the app or any app's share sheet.
- **Drag and drop to your phone.** On the desktop, drag a file to the shelf at the edge of
  the screen and drop it on a device.
- **Your devices work as one.** Paired devices form an ecosystem: what they send arrives
  straight away, with no Accept prompt, ready to paste.
- **Home-screen widget.** Your devices, one tap from sending.
- **Tap to share contacts.** Hold two Android phones back to back.

Connections are encrypted directly between devices with TLS 1.3. Pairing is confirmed by
checking that both screens show the same three shapes and the same number, so only devices
you approve can reach each other.

<p align="center">
  <img src="docs/images/windows.png" alt="Relay on Windows" width="760">
</p>

<p align="center">
  <img src="docs/images/phone-devices.png" width="220">
  <img src="docs/images/phone-send.png" width="220">
  <img src="docs/images/phone-help.png" width="220">
</p>

## Getting Relay

| Device | Where |
| --- | --- |
| Android 10+ | Google Play, or the APK on the [releases page](../../releases) |
| Windows 10/11 | [Releases](../../releases) |
| Linux | [Releases](../../releases) — `.deb` and `.rpm` |
| iPhone, iPad, Mac | In the works |

## Installing on a computer

The desktop builds are **not code-signed**. A signing certificate costs a few hundred
dollars a year and Relay sells nothing, so Windows and some Linux tools will say the app
comes from an unknown publisher. Nothing about Relay is hidden: the whole source is in
this repository, and you can build it yourself instead of trusting a download.

### Windows 10 and 11

1. Download the Windows zip from the [releases page](../../releases).
2. Right-click the zip, choose **Properties**, tick **Unblock**, then **OK**. Windows marks
   everything from the internet; unblocking once here saves warnings later.
3. Extract it somewhere permanent, for example a `Relay` folder inside your user folder.
4. Run `Relay.exe`. If **Windows protected your PC** appears, choose **More info**, then
   **Run anyway**.
5. If Windows says **Smart App Control blocked this app**, that feature allows signed apps
   only. You can either turn Smart App Control off in Windows Security, under
   *App & browser control* — a system-wide decision worth thinking about — or build Relay
   from source with the instructions below.
6. On first launch, Windows Firewall asks about network access. Tick **Private networks**
   and allow it, otherwise your phone cannot reach the computer.

Relay then sits in the system tray. Closing the window keeps it running; **Quit** in the
tray menu stops it. Turn on **Start Relay when I sign in** in Settings to have it ready
after every restart.

### Linux

Install the package for your distribution, then launch Relay from the applications menu:

```bash
sudo dpkg -i relay_1.0.0_amd64.deb    # Debian, Ubuntu, Mint
sudo rpm -i relay-1.0.0.x86_64.rpm    # Fedora, openSUSE
```

If your firewall is strict, allow TCP port 47800 and UDP port 47801 on the local network.

### Checking a download

Each release lists SHA-256 checksums. Compare one before running the app:

```powershell
Get-FileHash .\Relay-1.0.0-windows-x64.zip -Algorithm SHA256
```

```bash
sha256sum Relay-1.0.0-windows-x64.zip
```

## Building from source

Requires JDK 17+ and the Android SDK (compileSdk 37).

```bash
./gradlew :app:assembleDebug           # Android
./gradlew :desktop:run                 # desktop app
./gradlew test :app:testDebugUnitTest  # the test suite
./gradlew :desktop:createDistributable -Prelay.packagingJdk=/path/to/jdk17
```

Release builds are signed only when `keystore.properties` exists; without it the release
build is produced unsigned, so anyone can build Relay for themselves.

## How it is put together

| Module | What lives there |
| --- | --- |
| `core:protocol` | The Relay protocol: messages, framing, versions |
| `core:security` | Identities, certificates, pairing codes |
| `core:discovery` | Finding devices on the local network |
| `core:transfer` | The transfer engine: chunks, resume, checksums |
| `core:node` | The engine that ties those together |
| `core:designsystem` | Relay's Material 3 Expressive design system, shared by both apps |
| `core:data` | Android storage: database, settings, files |
| `app` | The Android app |
| `desktop` | The Windows, Linux and macOS app |

The core modules are plain JVM code with no Android or UI dependencies, and are covered by
the test suite.

## Privacy

Relay has no account and no server: what you send goes straight from one of your devices to
another. Optional anonymous usage statistics are off unless you turn them on in Settings.
See [docs/PRIVACY.md](docs/PRIVACY.md).

## Licence

[Apache 2.0](LICENSE).

<p align="center">
  <img src="site/images/icon.png" alt="Relay" width="96">
</p>

<h1 align="center">Relay</h1>

<p align="center">Your devices, together. Files, clipboard and links between your phone and your computer, over your own Wi-Fi. No account, no cloud.</p>

## Download

| Device | Where |
| --- | --- |
| Android 10+ | [Google Play](https://play.google.com/store/apps/details?id=com.vythera.relay) |
| Windows 10/11 | [Releases](../../releases/latest) — `Relay-x.y.z.exe` installer, or the portable zip |
| macOS 12+, Apple Silicon | [Releases](../../releases/latest) — `.dmg` |
| Linux | [Releases](../../releases/latest) — `.deb` and `.rpm` |
| iPhone and iPad | In the works |

## Installing on a computer

The desktop builds are **not code-signed**, so your computer will say the publisher is
unknown.

### Windows

Use the **installer** (`Relay-x.y.z.exe`). Run it; at **Windows protected your PC**,
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
sudo dpkg -i relay_x.y.z-1_amd64.deb      # Debian, Ubuntu, Mint, Pop!_OS
sudo rpm -i relay-x.y.z-1.x86_64.rpm      # Fedora, openSUSE, RHEL
```

## Connecting two devices

1. Install Relay on both.
2. Put them on the same Wi-Fi, or connect one to the other's hotspot.
3. Pick the other device, check that both screens show the same three shapes, and
   confirm. They find each other by themselves from then on.

## Privacy

Relay sends everything directly between your devices, encrypted with TLS. Nothing goes
through a server. See the [privacy policy](https://nikhilkain.github.io/relay-site/privacy.html).

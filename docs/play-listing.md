# Google Play listing for Relay

Everything Play asks for, written out. Nothing here has been submitted: the declarations
below are statements the developer makes, so they need a person to read and confirm them.

## Store listing

**App name (30)**
`Relay: Share Between Devices`

**Short description (80)**
`Files, clipboard and links between your phone and PC. No account, no cloud.`

**Full description (4000)**

```
Relay moves files, photos, text and links between your own devices over your Wi-Fi.
Nothing goes through a server, there is no account to make, and nothing is uploaded
anywhere.

COPY HERE, PASTE THERE
Copy something on your computer and paste it on your phone. Text, screenshots, images
and whole files, both directions.

SEND ANYTHING
Photos, videos, documents, folders, notes and links. Share to a device straight from
any app's share sheet, or from Relay itself.

DRAG AND DROP TO YOUR PHONE
On your computer, drag a file to the shelf at the edge of the screen and drop it on
your phone. It arrives ready to paste.

YOUR DEVICES WORK AS ONE
Devices you have paired act as one ecosystem: what they send arrives straight away,
with no Accept button to press.

HOME SCREEN WIDGET
Your devices on the home screen. Tap one, choose a file, done.

TAP TO SHARE CONTACTS
Hold two phones back to back to swap contact cards.

PRIVATE BY DESIGN
Connections are encrypted directly between your devices with TLS. Pairing is confirmed
by checking that both screens show the same three shapes and the same number, so only
devices you approve can connect. No account, no cloud, no tracking: optional anonymous
usage statistics are off unless you turn them on.

WORKS WITH
Android phones and tablets, Windows and Linux computers. iPhone, iPad and Mac are in
the works. The computer app is a free download from GitHub.
```

**Category** Tools · **Tags** File sharing, Productivity
**Contact email** (the developer's) · **Privacy policy URL** must be a public URL; publish
`docs/PRIVACY.md` (GitHub Pages or the raw file once the repository is public).

## Data safety form

| Question | Answer |
| --- | --- |
| Does your app collect or share user data? | Yes — only if the user turns statistics on |
| Data types | App activity › "Other actions" (anonymous events), plus Firebase's app-instance ID and technical info (device model, OS version, country) |
| Collected or shared? | Collected. Not shared with third parties beyond the analytics provider (Google Firebase) |
| Processed ephemerally? | No |
| Required or optional? | Optional — off by default, switch in Settings › Privacy |
| Encrypted in transit? | Yes |
| Can users request deletion? | Yes — turning the setting off stops collection; uninstalling removes local data |
| Files and docs, photos, messages, contacts | **Not collected.** They are sent device to device and never reach the developer |

If analytics ship switched off and unused, the honest answer to the first question is
still "Yes" while the code is present and the user can enable it.

## Content rating questionnaire

Category "Utility". No violence, sexual content, profanity, drugs, gambling. User-generated
content: no (nothing is shared with other users, only between the user's own devices). No
social features, no location, no personal details shared.

Expected rating: Everyone / PEGI 3.

## Ads, target audience, news

- Contains ads: **No**
- In-app purchases: **No**
- Target age: 13+ (not designed for children)
- News app: **No**
- Government app: **No**

## App access

Relay needs no sign-in. All features are available without credentials; a reviewer needs a
second device (or two emulators on one network) to see pairing.

## Permissions to justify

| Permission | Why |
| --- | --- |
| `READ_LOGS` | Optional instant clipboard: Android only tells an app the clipboard changed while it is in front, so Relay watches for the system's own "clipboard access denied" line about itself. Nothing from the log is stored or sent. Granted by the user with adb; the app works fully without it |
| `SYSTEM_ALERT_WINDOW` | The one-pixel window that takes focus for an instant so the clipboard can be read |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Keeping the connection to the user's other devices while the app is not on screen |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Same reason; asked for, never required |
| `NFC` | Sharing a contact card by touching two phones |

`READ_LOGS` is a permission Google Play reviews closely. If it puts the release at risk,
the instant clipboard can be cut from the Play build (`instantClipboard` in settings) and
kept for the GitHub build.

## Release

- Bundle: `app/build/outputs/bundle/release/app-release.aab`, version 1.0.0 (code 1)
- Signed with the upload key in `keystore/relay-upload.jks`; Play App Signing re-signs it
- Countries: all, unless the developer prefers a staged rollout

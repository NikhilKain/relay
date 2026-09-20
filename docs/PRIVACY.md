# Relay privacy policy

_Last updated: 20 September 2026_

Relay is made by Vythera. This policy explains what Relay does with your information. The
short version: your files, text and clipboard go directly between your own devices, and
we never receive them.

## What Relay does not collect

- **Your content.** Files, photos, text, links and clipboard contents are sent straight
  from one of your devices to another over your local network. They never reach us, and
  there is no Relay server they could pass through.
- **Accounts.** Relay has no sign-in, so we hold no name, email address or profile.
- **Contacts.** The contact card you can share by holding two phones together is the one
  you type into Relay yourself, and it is sent only to the device you touch.

## What stays on your device

- The devices you have paired with, their names and their public keys.
- A history of what you sent and received, so the app can show it to you.
- Your settings, and the files you received, in the folder you chose.

Removing the app removes all of it.

## Optional usage statistics

Relay can send anonymous usage statistics through Google Firebase Analytics. **This is off
unless you turn it on** in Settings › Privacy.

When it is on, Relay reports that something happened — a device was paired, a transfer
finished or failed, the clipboard was shared — along with a coarse category such as
"photos". It never includes file names, file contents, text, clipboard contents, device
names, network addresses, or anything identifying the devices you pair with. Firebase also
collects an app-instance identifier and general technical information such as the app
version and country; see Google's [Firebase privacy page](https://firebase.google.com/support/privacy).

You can turn it off again at any time in the same place, which stops collection.

## Permissions Relay asks for

- **Local network / Wi-Fi state.** To find and reach your other devices.
- **Notifications.** To tell you what arrived and to show transfers in progress.
- **Run in the background.** So your devices can reach this one when the app is not open.
- **Display over other apps** and **read logs** (optional, for instant clipboard). Relay
  looks only for the moment Android reports that a clipboard listener was refused, so it
  knows something was copied. Nothing from the log is stored or sent.
- **NFC** (optional). To share your contact card by holding two phones together.

## Children

Relay is not directed at children.

## Contact

Questions about this policy: open an issue on the Relay repository.

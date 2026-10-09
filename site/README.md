# The Relay website

`index.html` is the landing page and `privacy.html` is the privacy policy Google Play
points at. Both are plain files with no build step: edit them and push.

The download buttons are generated from two things near the bottom of `index.html`:

- `REPO` — the repository whose **latest release** the buttons link to.
- `FILES` — the asset filenames in that release. Change these when the version changes.

`SUPPORT_URL` on the same line is the Telegram channel, still a placeholder.

To serve it, turn on GitHub Pages for this repository once it is public. Pages publishes
either the repository root or `docs/`, so this folder needs a small Actions workflow, or
move it to `docs/`. Until then the site is served from the old `relay-site` repository.

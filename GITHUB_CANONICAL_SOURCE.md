# STARX24 canonical source

This repository uses the files in the repository root as the canonical source:

- Android client source is under `app/`.
- Firebase Realtime Database rules are in `database.rules.json`.
- Render deploys the Node backend from `backend/`.
- Render secrets belong in the Render environment, never in this repository or an APK.

The Android admin builds call `POST /admin/notifyParticipants` for room-release push notifications. Firebase Admin credentials and FCM tokens remain on the backend.

`local.properties` is intentionally not committed. Copy `app/local.properties.example` only when building locally and set the local Android SDK path for that machine.
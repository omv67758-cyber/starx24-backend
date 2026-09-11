# STARX24 secure ZapUPI + Firebase setup

The payment key is **not** stored in the Android app. The app sends the signed-in user's Firebase ID token to the Render backend. The backend verifies that token, creates the ZapUPI order, verifies the webhook with ZapUPI, and credits the user's Firebase wallet exactly once.

## 1. Deploy the backend on Render

Create a Render Web Service from this project:

- Root Directory: `backend`
- Build Command: `npm install`
- Start Command: `npm start`
- Health Check Path: `/health`

Or use the included `render.yaml` blueprint.

Add these Render environment variables:

| Variable | Value |
| --- | --- |
| `ZAPUPI_KEY` | Your ZapUPI key, entered as a Render Secret |
| `ZAPUPI_MODE` | `TEST` while testing |
| `FIREBASE_SERVICE_ACCOUNT` | The complete Firebase Admin service-account JSON |
| `FIREBASE_DATABASE_URL` | `https://tournament-app-82b33-default-rtdb.firebaseio.com` |
| `PUBLIC_BASE_URL` | The final HTTPS Render URL, without a trailing slash |

`FIREBASE_SERVICE_ACCOUNT` is **not** the Android `google-services.json` file. Download a Firebase Admin service-account JSON from Firebase Console → Project settings → Service accounts. Paste the complete JSON as one Render secret value. Do not commit it or put it inside the APK.

The backend also accepts `FIREBASE_SERVICE_ACCOUNT_BASE64` instead of the raw JSON when your hosting dashboard has trouble with multiline values.

After the first Render deploy, set `PUBLIC_BASE_URL` to the exact Render URL and redeploy. In the ZapUPI dashboard, configure the webhook URL as:

```text
https://YOUR-SERVICE.onrender.com/zapupiWebhook
```

The spelling is `zapupiWebhook` with the final `k`. The dashboard's Test button may send a placeholder order ID; the backend acknowledges that probe with HTTP 200 without crediting any wallet. Real payment callbacks are still verified against the order created in Firebase.

## 2. Point the Android app to Render

Copy `app/local.properties.example` to `app/local.properties` and set:

```properties
backend.base.url=https://YOUR-SERVICE.onrender.com
remote.maintenance.enabled=false
```

Build the user flavor from Android Studio. The URL is public configuration; the ZapUPI key must never be added to `local.properties`, Java, XML, or Gradle.

You can also provide the URL at build time:

```bash
./gradlew assembleUserDebug -Pbackend.base.url=https://YOUR-SERVICE.onrender.com
```

## 3. Firebase rules and wallet behavior

The backend uses Firebase Admin SDK, so it can credit `users/<uid>/wallet/balance` without exposing admin credentials to the app. The Android wallet now reads that Firebase balance instead of a local demo preference.

Keep the existing Firebase Realtime Database rules deployed, and test with `ZAPUPI_MODE=TEST` first. Move to live mode only after ZapUPI account/compliance approval and a successful end-to-end test.

## Notifications and security

- Room-release push notifications are sent through `/admin/notifyParticipants`. The Android app does not contain a Firebase service-account file or FCM OAuth private key.
- The payment key previously shared in chat should be rotated in ZapUPI before production use, because secrets pasted into chat or committed files must be treated as exposed. Store the replacement only in Render's secret environment variable.
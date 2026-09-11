# STARX24 Updated Deployment Package

## Separate Firebase Android applications

The supplied Firebase registrations are wired to the following flavors:

| APK | Package ID | Firebase app registration |
|---|---|---|
| User | `com.tournament.user` | supplied User app |
| Payment Admin | `com.tournament.payment` | supplied Payment app |
| Match Admin | `com.tournament.match` | supplied Match app |
| Master Control | `com.tournament.admin` | supplied Admin/Master app |

Each flavor uses a different launcher manifest and a different login presentation. The Master Control login accepts Gmail accounts only. The backend repeats this check for all Master-only provisioning requests.

## Firebase Realtime Database rules

The updated rules are in `database.rules.json`. Upload them from the project root after authenticating with the Firebase CLI:

```bash
firebase login
firebase use tournament-app-82b33
firebase deploy --only database
```

The first Master Control account must be seeded once by an administrator in Firebase Authentication and Realtime Database. Create the Firebase Auth user with a Gmail address, then create `adminUsers/{uid}` with:

```json
{
  "uid": "AUTH_UID",
  "email": "master@gmail.com",
  "role": "MASTER_CONTROL",
  "enabled": true,
  "status": "ACTIVE",
  "createdAt": { ".sv": "timestamp" },
  "updatedAt": { ".sv": "timestamp" }
}
```

After that, the Master Control APK can generate Payment Admin and Match Admin accounts. Generated passwords are returned once by the backend and are not stored in plaintext in the database.

## Backend deployment

The updated backend is in `backend/index.js`. It includes:

- `/admin/provision`, which requires a valid Firebase ID token from an active Master Control account. It creates the Firebase Authentication user, writes `adminUsers/{uid}`, and creates an audit log entry.
- `/admin/notifyParticipants`, which sends room-release notifications from the trusted backend to only the selected tournament or match participants. FCM service-account credentials and participant tokens stay server-side.

Render environment variables:

```text
FIREBASE_SERVICE_ACCOUNT=<Firebase Admin service account JSON>
FIREBASE_DATABASE_URL=https://tournament-app-82b33-default-rtdb.firebaseio.com
ZAPUPI_KEY=<server-side ZapUPI key>
ZAPUPI_MODE=TEST
PUBLIC_BASE_URL=https://YOUR-RENDER-SERVICE.onrender.com
```

Deploy with Render using the existing `render.yaml`:

```text
Root directory: backend
Build command: npm install
Start command: npm start
Health check: /health
```

Do not place `FIREBASE_SERVICE_ACCOUNT`, `ZAPUPI_KEY`, any FCM service-account JSON, or any private key in the Android project or APK.

## APK build commands

```bash
./gradlew assembleUserDebug
./gradlew assemblePaymentAdminDebug
./gradlew assembleMatchAdminDebug
./gradlew assembleMasterControlDebug
```

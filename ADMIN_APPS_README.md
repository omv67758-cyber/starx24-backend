# STARX24 Admin Apps

The Android project now produces four separate applications from one source tree. The existing user application is the `user` flavor; the privileged products are `paymentAdmin`, `matchAdmin`, and `masterControl`.

| Product | Application ID | Launcher | Scope |
|---|---|---|---|
| User | `com.tournament.user` | `LoginActivity` | Tournament user experience |
| Payment Admin | `com.tournament.payment` | `PaymentAdminActivity` | Withdrawals, payment status, user notification |
| Match Admin | `com.tournament.match` | `MatchAdminActivity` | Banners, categories, matches and room release |
| Master Control | `com.tournament.admin` | `MasterControlActivity` | Admin accounts, system oversight and audit |

## Security model

Admin authentication still uses Firebase Authentication. After sign-in, the client reads `adminUsers/{uid}` and requires both `enabled == true` and `status == ACTIVE`. The Firebase Realtime Database rules independently validate the same status and role; hiding an action in the Android UI is not the security boundary.

The three admin login screens use separate flavor presentation and role-specific UI. Master Control accepts Gmail accounts only. Only Master Control can call the backend provisioning endpoint to generate Payment Admin or Match Admin Firebase accounts and one-time credentials.

Payment approval uses a Firebase transaction on `withdrawals/{id}/status`. Only `PENDING -> PAID` is accepted, so two concurrent admins cannot both complete the same request. The successful transition stores `paidAt`, `handledBy`, `updatedAt`, writes an audit event, and creates an internal user notification. No SMS or WhatsApp integration is implied.

Room credentials are not released by merely editing a match. Match Admin must explicitly release them through a backend-authorized operation, and the user app should continue to apply its existing release-time/participant checks.

## Build

From the project root, use the Android Gradle wrapper supplied by the build environment (or generate one with a locally installed Gradle version):

```bash
./gradlew assembleUserRelease
./gradlew assemblePaymentAdminRelease
./gradlew assembleMatchAdminRelease
./gradlew assembleMasterControlRelease
```

Debug equivalents are available as `assembleUserDebug`, `assemblePaymentAdminDebug`, `assembleMatchAdminDebug`, and `assembleMasterControlDebug`.

Privileged Firebase service-account credentials and payment gateway keys remain backend-only. The Android APK contains only the public Firebase configuration and backend URL.

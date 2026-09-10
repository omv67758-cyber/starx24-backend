# Render deployment checklist

1. Push this project to a private Git repository.
2. In Render, create a Web Service from the repository.
3. Set Root Directory to `backend`, Build Command to `npm install`, and Start Command to `npm start`.
4. Add the variables listed in `PAYMENT_SETUP.md`.
5. Deploy once, copy the service URL, set `PUBLIC_BASE_URL` to that URL, and deploy again.
6. Use the same URL in Android `backend.base.url`.
7. Set the ZapUPI webhook to `/zapupiWebhook`.

If you downloaded a ZIP, do not keep the outer `starx24_share` folder as the
repository root while using the settings above. Put the contents of that folder
(`backend`, `app`, `render.yaml`, etc.) at the repository root. Otherwise set
the Render Root Directory to `starx24_share/backend`.

Never upload `FIREBASE_SERVICE_ACCOUNT` to the Android project. The Android `app/google-services.json` is only the client Firebase configuration and is not a server credential.
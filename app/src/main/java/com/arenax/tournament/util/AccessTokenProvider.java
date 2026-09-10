package com.arenax.tournament.util;

import android.content.Context;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * !! SECURITY WARNING !!
 * Loads the service-account private key directly from app assets and signs
 * OAuth2 JWTs on-device. The key ships inside the APK — anyone who
 * decompiles the ADMIN build can extract assets/service-account.json and
 * send unlimited FCM messages under this Firebase project.
 *
 * Reduce blast radius:
 *  - Only bundle assets/service-account.json in the `admin` product flavor
 *    (see app/build.gradle "admin" flavor's own src/admin/assets/), never
 *    in the `user` flavor — normal players' APKs must never contain this key.
 *  - Create a DEDICATED service account with ONLY the
 *    "Firebase Cloud Messaging API Admin" IAM role (nothing broader,
 *    no Editor/Owner role) so a leaked key can only send notifications,
 *    not touch the database, storage, or billing.
 *  - Never publish/share the admin APK outside the admin team.
 *  - Rotate (delete + regenerate) the key immediately if you suspect leakage.
 *
 * Ported from the android-messenger-app prototype (AccessTokenProvider.java).
 */
public class AccessTokenProvider {

    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final String ASSET_FILE = "service-account.json";

    private static String cachedToken;
    private static long cachedTokenExpiryMillis = 0;

    /** Returns a valid access token, reusing a cached one if it hasn't expired yet. */
    public static synchronized String getAccessToken(Context context) throws Exception {
        if (cachedToken != null && System.currentTimeMillis() < cachedTokenExpiryMillis) {
            return cachedToken;
        }

        JSONObject serviceAccount = loadServiceAccountJson(context);
        String clientEmail = serviceAccount.getString("client_email");
        String privateKeyPem = serviceAccount.getString("private_key");
        String tokenUri = serviceAccount.optString("token_uri", "https://oauth2.googleapis.com/token");

        String jwt = buildSignedJwt(clientEmail, privateKeyPem, tokenUri);
        String accessToken = exchangeJwtForAccessToken(tokenUri, jwt);

        cachedToken = accessToken;
        // FCM tokens last ~1hr; refresh a few minutes early to be safe
        cachedTokenExpiryMillis = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(55);

        return accessToken;
    }

    private static JSONObject loadServiceAccountJson(Context context) throws IOException, org.json.JSONException {
        InputStream is = context.getAssets().open(ASSET_FILE);
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return new JSONObject(sb.toString());
    }

    private static String buildSignedJwt(String clientEmail, String privateKeyPem, String tokenUri) throws Exception {
        long nowSeconds = System.currentTimeMillis() / 1000L;
        long expSeconds = nowSeconds + 3600;

        JSONObject header = new JSONObject();
        header.put("alg", "RS256");
        header.put("typ", "JWT");

        JSONObject claims = new JSONObject();
        claims.put("iss", clientEmail);
        claims.put("scope", SCOPE);
        claims.put("aud", tokenUri);
        claims.put("iat", nowSeconds);
        claims.put("exp", expSeconds);

        String headerB64 = base64UrlEncode(header.toString().getBytes(StandardCharsets.UTF_8));
        String claimsB64 = base64UrlEncode(claims.toString().getBytes(StandardCharsets.UTF_8));
        String signingInput = headerB64 + "." + claimsB64;

        PrivateKey privateKey = parsePrivateKey(privateKeyPem);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        byte[] signedBytes = signature.sign();

        return signingInput + "." + base64UrlEncode(signedBytes);
    }

    private static PrivateKey parsePrivateKey(String pem) throws Exception {
        String cleaned = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.decode(cleaned, Base64.DEFAULT);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePrivate(spec);
    }

    private static String exchangeJwtForAccessToken(String tokenUri, String jwt) throws IOException, org.json.JSONException {
        OkHttpClient client = new OkHttpClient();
        FormBody form = new FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                .add("assertion", jwt)
                .build();

        Request request = new Request.Builder()
                .url(tokenUri)
                .post(form)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Token exchange failed (" + response.code() + "): " + body);
            }
            JSONObject json = new JSONObject(body);
            return json.getString("access_token");
        }
    }

    private static String base64UrlEncode(byte[] data) {
        return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }
}

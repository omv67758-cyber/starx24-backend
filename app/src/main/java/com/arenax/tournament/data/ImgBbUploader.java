package com.arenax.tournament.data;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Image upload via the ImgBB API (https://api.imgbb.com). Simplest option of
 * the three tried for this app: one free API key, one HTTP call, and imgbb
 * hands back a permanent public image URL -- no Firebase Storage bucket, no
 * Telegram bot/channel to set up.
 *
 * SETUP (one-time):
 * 1. Go to https://api.imgbb.com/ and sign in (free, Google login works).
 * 2. Copy the API key shown on that page.
 * 3. Paste it into API_KEY below.
 *
 * SECURITY NOTE: like any key embedded in a client app, this can be extracted
 * by decompiling the APK. The imgbb API key only allows *uploading* images to
 * your imgbb account (it can't read/delete other users' data or cost you
 * money beyond imgbb's free-tier limits), so the blast radius if it leaks is
 * small -- worst case someone else uploads images under your imgbb account.
 * If that ever matters, regenerate the key from the imgbb dashboard.
 */
public final class ImgBbUploader {
    // TODO: paste your key from https://api.imgbb.com/
    private static final String API_KEY = "REPLACE_WITH_IMGBB_API_KEY";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ImgBbUploader() {}

    public static void uploadImage(Context context, Uri imageUri, StorageUploader.Callback callback) {
        if (imageUri == null) {
            callback.onFailure("Select an image first");
            return;
        }
        if (API_KEY.startsWith("REPLACE_")) {
            callback.onFailure("Image uploader is not configured yet (ImgBB API_KEY missing).");
            return;
        }

        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                byte[] imageBytes = readBytes(appContext, imageUri);
                String base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP);
                String body = "image=" + URLEncoder.encode(base64Image, "UTF-8");

                HttpURLConnection conn = (HttpURLConnection) new URL(
                        "https://api.imgbb.com/1/upload?key=" + API_KEY).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(30_000);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                String response = readStream(stream);
                android.util.Log.d("ImgBbUpload", "HTTP " + code + " response: " + response);

                JSONObject json = new JSONObject(response);
                if (code < 200 || code >= 300 || !json.optBoolean("success", false)) {
                    String description = json.optJSONObject("error") != null
                            ? json.optJSONObject("error").optString("message", "unknown error")
                            : ("HTTP " + code);
                    throw new IllegalStateException("ImgBB upload failed: " + description);
                }
                String url = json.getJSONObject("data").getString("url");
                postSuccess(callback, url);
            } catch (Exception error) {
                postFailure(callback, error.getMessage() == null ? "Image upload failed" : error.getMessage());
            }
        }).start();
    }

    private static byte[] readBytes(Context context, Uri uri) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Could not read selected image");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    private static String readStream(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toString("UTF-8");
    }

    private static void postSuccess(StorageUploader.Callback callback, String url) {
        MAIN.post(() -> callback.onSuccess(url));
    }

    private static void postFailure(StorageUploader.Callback callback, String message) {
        MAIN.post(() -> callback.onFailure(message));
    }
}

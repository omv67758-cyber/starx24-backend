package com.arenax.tournament.data;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Image upload via a Telegram bot instead of Firebase Storage.
 *
 * WHY THIS EXISTS: "Image upload failed: Object does not exist at location"
 * from Firebase Storage usually means the project's default Storage bucket
 * was never provisioned (Storage > Get started was never clicked in the
 * Firebase console) or the project is on the Spark plan without Storage
 * enabled. Sending the image to a private Telegram channel and reading back
 * its public file URL sidesteps that entirely -- no Firebase Storage setup
 * required.
 *
 * SECURITY TRADE-OFF (read this before shipping):
 * BOT_TOKEN lives in the compiled app, so anyone who decompiles the APK can
 * extract it and fully control that bot (post/delete in any chat it's in,
 * read its messages, etc). Mitigate by:
 *   - Using a bot dedicated to this one purpose -- never your personal or a
 *     shared bot.
 *   - Only adding that bot as admin to one private channel used purely as
 *     image storage, nothing else.
 *   - If this app matters commercially, move BOT_TOKEN server-side later
 *     (e.g. a small Cloud Function the app calls instead) so the token never
 *     ships in the APK at all.
 *
 * SETUP:
 * 1. Message @BotFather on Telegram -> /newbot -> follow prompts -> copy the
 *    token it gives you into BOT_TOKEN below.
 * 2. Create a new Telegram channel (private is fine) -> add your bot as an
 *    administrator of that channel.
 * 3. Get the channel's numeric ID: easiest way is to post any message in the
 *    channel, forward it to @userinfobot (or @JsonDumpBot), and read the
 *    "chat":{"id": ...} value -- it will look like -1001234567890.
 *    Put that (as a string, including the leading -100) into CHANNEL_ID.
 */
public final class TelegramImageUploader {
    // TODO: fill these in -- see SETUP above. Do not commit real values to a
    // public repo; keep this file private or move to a gitignored config.
    private static final String BOT_TOKEN = "REPLACE_WITH_YOUR_BOT_TOKEN";
    private static final String CHANNEL_ID = "REPLACE_WITH_YOUR_CHANNEL_ID";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private TelegramImageUploader() {}

    public static void uploadImage(Context context, Uri imageUri, StorageUploader.Callback callback) {
        if (imageUri == null) {
            callback.onFailure("Select an image first");
            return;
        }
        if (BOT_TOKEN.startsWith("REPLACE_") || CHANNEL_ID.startsWith("REPLACE_")) {
            callback.onFailure("Telegram uploader is not configured yet (BOT_TOKEN / CHANNEL_ID).");
            return;
        }

        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                byte[] imageBytes = readBytes(appContext, imageUri);
                String fileId = sendPhoto(imageBytes);
                String filePath = resolveFilePath(fileId);
                String publicUrl = "https://api.telegram.org/file/bot" + BOT_TOKEN + "/" + filePath;
                postSuccess(callback, publicUrl);
            } catch (Exception error) {
                postFailure(callback, error.getMessage() == null ? "Image upload failed" : error.getMessage());
            }
        }).start();
    }

    private static String sendPhoto(byte[] imageBytes) throws Exception {
        String boundary = "----StarX24Boundary" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeFormField(body, boundary, "chat_id", CHANNEL_ID);
        writeFileField(body, boundary, "photo", "upload.jpg", "image/jpeg", imageBytes);
        writeFinalBoundary(body, boundary);

        HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://api.telegram.org/bot" + BOT_TOKEN + "/sendPhoto").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toByteArray());
        }

        JSONObject json = readJson(conn);
        if (!json.optBoolean("ok", false)) {
            throw new IllegalStateException("Telegram upload failed: " + json.optString("description", "unknown error"));
        }
        JSONArray photos = json.getJSONObject("result").getJSONArray("photo");
        // Telegram returns several sizes; the last entry is the largest.
        JSONObject largest = photos.getJSONObject(photos.length() - 1);
        return largest.getString("file_id");
    }

    private static String resolveFilePath(String fileId) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://api.telegram.org/bot" + BOT_TOKEN + "/getFile?file_id=" + fileId).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(15_000);

        JSONObject json = readJson(conn);
        if (!json.optBoolean("ok", false)) {
            throw new IllegalStateException("Could not resolve uploaded image path.");
        }
        return json.getJSONObject("result").getString("file_path");
    }

    private static JSONObject readJson(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String response = readStream(stream);
        android.util.Log.d("TelegramUpload", "HTTP " + code + " response: " + response);
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Telegram request failed (" + code + "): " + response);
        }
        return new JSONObject(response);
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

    private static void writeFormField(ByteArrayOutputStream body, String boundary, String name, String value) throws Exception {
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write((value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFileField(ByteArrayOutputStream body, String boundary, String fieldName,
                                        String fileName, String mimeType, byte[] fileBytes) throws Exception {
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Type: " + mimeType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(fileBytes);
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFinalBoundary(ByteArrayOutputStream body, String boundary) throws Exception {
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void postSuccess(StorageUploader.Callback callback, String url) {
        MAIN.post(() -> callback.onSuccess(url));
    }

    private static void postFailure(StorageUploader.Callback callback, String message) {
        MAIN.post(() -> callback.onFailure(message));
    }
}

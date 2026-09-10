package com.arenax.tournament.util;

import org.json.JSONObject;

import java.io.IOException;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Sends messages directly to fcm.googleapis.com/v1 using an already-generated
 * OAuth2 Bearer access token (see AccessTokenProvider).
 *
 * NOTE: this class must be called from a background thread, never the main thread.
 *
 * Ported from the android-messenger-app prototype (FcmSender.java) so the
 * ADMIN flavor can notify only a specific tournament/match's joined
 * participants when a room is released — instead of the old GLOBAL
 * in-app-only notification that every user saw.
 */
public class FcmSender {

    // Same Firebase project used by tournaments/matches/users (see google-services.json).
    private static final String PROJECT_ID = "tournament-app-82b33";

    private static final OkHttpClient client = new OkHttpClient();

    /** Sends one notification to one FCM token. Throws on failure. */
    public static String sendToToken(String accessToken, String targetToken,
                                      String title, String body, String imageUrl) throws IOException, org.json.JSONException {
        JSONObject notification = new JSONObject();
        notification.put("title", title);
        notification.put("body", body);
        if (imageUrl != null && !imageUrl.isEmpty()) {
            notification.put("image", imageUrl);
        }

        JSONObject message = new JSONObject();
        message.put("token", targetToken);
        message.put("notification", notification);

        JSONObject root = new JSONObject();
        root.put("message", message);

        RequestBody requestBody = RequestBody.create(root.toString(), MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url("https://fcm.googleapis.com/v1/projects/" + PROJECT_ID + "/messages:send")
                .post(requestBody)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("FCM error (" + response.code() + "): " + respBody);
            }
            return respBody;
        }
    }

    /** Sends the same notification to a list of tokens, one by one, and reports a summary. */
    public static void sendToAllTokens(String accessToken, List<String> tokens,
                                        String title, String body, String imageUrl,
                                        SendResultCallback callback) {
        StringBuilder errorLog = new StringBuilder();
        int successCount = 0;

        for (String t : tokens) {
            try {
                sendToToken(accessToken, t, title, body, imageUrl);
                successCount++;
            } catch (Exception e) {
                errorLog.append("• Failed for a token: ").append(e.getMessage()).append("\n");
            }
        }

        callback.onDone(successCount, tokens.size(), errorLog.toString());
    }

    public interface SendResultCallback {
        void onDone(int successCount, int total, String errorLog);
    }
}

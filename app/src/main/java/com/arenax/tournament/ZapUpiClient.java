package com.arenax.tournament;

import android.os.Handler;
import android.os.Looper;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Creates a wallet payment through the private Render backend.
 *
 * The ZapUPI key is intentionally not present in this Android project. The
 * backend verifies the Firebase ID token before it creates an order.
 */
public final class ZapUpiClient {
    public interface Callback {
        void onSuccess(String paymentUrl, String orderId);
        void onError(String message);
    }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    /*
     * Render services can sleep between requests. The default OkHttp timeout is
     * only 10 seconds, which makes a normal cold start look like a network
     * outage in the app. Order creation also waits for Firebase verification
     * and the ZapUPI response, so allow the complete operation enough time.
     */
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ZapUpiClient() { }

    public static void createOrder(int amountInr, String customerMobile, Callback callback) {
        if (amountInr < 1 || amountInr > 100000) {
            callback.onError("Enter an amount between ₹1 and ₹1,00,000");
            return;
        }

        String baseUrl = BuildConfig.STARX_API_BASE_URL == null
                ? "" : BuildConfig.STARX_API_BASE_URL.trim();
        if (baseUrl.isEmpty() || baseUrl.contains("YOUR-SERVICE")) {
            callback.onError("Payment backend is not configured. Add backend.base.url before building.");
            return;
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            callback.onError("Please sign in before adding money.");
            return;
        }

        user.getIdToken(false)
                .addOnSuccessListener(result -> {
                    String idToken = result == null ? null : result.getToken();
                    if (idToken == null || idToken.trim().isEmpty()) {
                        MAIN.post(() -> callback.onError("Could not verify your Firebase session."));
                        return;
                    }
                    sendCreateOrder(baseUrl, idToken, amountInr, customerMobile, callback);
                })
                .addOnFailureListener(error ->
                        MAIN.post(() -> callback.onError("Could not verify your Firebase session.")));
    }

    private static void sendCreateOrder(
            String baseUrl,
            String idToken,
            int amountInr,
            String customerMobile,
            Callback callback
    ) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("amount", String.valueOf(amountInr));
            if (customerMobile != null && !customerMobile.trim().isEmpty()) {
                payload.put("mobile", customerMobile.trim());
            }

            String normalizedBaseUrl = baseUrl.endsWith("/")
                    ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            Request request = new Request.Builder()
                    .url(normalizedBaseUrl + "/createOrder")
                    .post(RequestBody.create(payload.toString(), JSON))
                    .addHeader("Authorization", "Bearer " + idToken)
                    .build();

            HTTP.newCall(request).enqueue(new okhttp3.Callback() {
                @Override public void onFailure(Call call, IOException error) {
                    String message = isTimeout(error)
                            ? "Payment server is waking up. Please try again in a few seconds."
                            : "Could not connect to the payment server. Please check your internet and try again.";
                    MAIN.post(() -> callback.onError(message));
                }

                @Override public void onResponse(Call call, Response response) {
                    try (Response bodyResponse = response) {
                        String body = bodyResponse.body() == null ? "" : bodyResponse.body().string();
                        if (!response.isSuccessful()) {
                            MAIN.post(() -> callback.onError(readServerError(body, response.code())));
                            return;
                        }

                        JSONObject result = new JSONObject(body);
                        String paymentUrl = findPaymentUrl(result);
                        String orderId = result.optString("order_id", "").trim();
                        if (paymentUrl.isEmpty() || orderId.isEmpty()) {
                            MAIN.post(() -> callback.onError("Payment provider returned an incomplete order."));
                            return;
                        }
                        MAIN.post(() -> callback.onSuccess(paymentUrl, orderId));
                    } catch (Exception error) {
                        MAIN.post(() -> callback.onError("Invalid payment response from backend."));
                    }
                }
            });
        } catch (Exception error) {
            MAIN.post(() -> callback.onError("Could not create payment order."));
        }
    }

    private static String readServerError(String body, int statusCode) {
        try {
            JSONObject json = new JSONObject(body);
            String message = json.optString("error", "").trim();
            if (!message.isEmpty()) return message;
        } catch (Exception ignored) {
            // Use a generic message below so provider details are not shown in the app.
        }
        return "Payment order failed (HTTP " + statusCode + ").";
    }

    private static boolean isTimeout(IOException error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.net.SocketTimeoutException) return true;
            current = current.getCause();
        }
        return false;
    }

    private static String findPaymentUrl(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String[] keys = {"payment_url", "paymentUrl", "checkout_url", "redirect_url", "pay_url", "payment_link", "url"};
            for (String key : keys) {
                String candidate = object.optString(key, "").trim();
                if (candidate.startsWith("http://") || candidate.startsWith("https://")) return candidate;
            }
            java.util.Iterator<String> names = object.keys();
            while (names.hasNext()) {
                String candidate = findPaymentUrl(object.opt(names.next()));
                if (!candidate.isEmpty()) return candidate;
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String candidate = findPaymentUrl(array.opt(i));
                if (!candidate.isEmpty()) return candidate;
            }
        } else if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.startsWith("http://") || text.startsWith("https://")) return text;
            if (text.startsWith("{") || text.startsWith("[")) {
                try {
                    return findPaymentUrl(new org.json.JSONTokener(text).nextValue());
                } catch (Exception ignored) {
                    // Not a JSON-encoded URL.
                }
            }
        }
        return "";
    }
}
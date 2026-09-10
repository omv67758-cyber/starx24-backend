package com.arenax.tournament.data;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import java.util.UUID;

/** Secure image upload through Firebase Storage. No service credential is shipped in the APK. */
public final class StorageUploader {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private StorageUploader() {}
    public interface Callback {
        void onSuccess(String fileUrl);
        void onFailure(String message);
    }
    public static void uploadImage(Context context, Uri imageUri, Callback callback) {
        if (imageUri == null) { callback.onFailure("Select an image first"); return; }
        StorageReference ref = FirebaseStorage.getInstance().getReference()
                .child("gameModes").child(UUID.randomUUID().toString() + ".jpg");
        ref.putFile(imageUri).continueWithTask(task -> {
            if (!task.isSuccessful()) throw task.getException() == null
                    ? new IllegalStateException("Image upload failed") : task.getException();
            return ref.getDownloadUrl();
        }).addOnSuccessListener(uri -> MAIN.post(() -> callback.onSuccess(uri.toString())))
          .addOnFailureListener(error -> MAIN.post(() -> callback.onFailure(
                  error.getMessage() == null ? "Image upload failed" : error.getMessage())));
    }
}

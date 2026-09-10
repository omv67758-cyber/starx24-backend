package com.arenax.tournament;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.FirebaseDatabase;
import com.arenax.tournament.data.FirebaseRepository;

public class MessageLoginActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_message_login);
        EditText email = findViewById(R.id.message_login_email), password = findViewById(R.id.message_login_password);
        findViewById(R.id.message_login_button).setOnClickListener(v -> {
            String e = email.getText().toString().trim(), p = password.getText().toString();
            if (e.isEmpty() || p.isEmpty()) { toast("Enter email and password"); return; }
            if (!FirebaseRepository.isFirebaseAvailable(this)) { toast("Firebase setup is unavailable. Check google-services.json."); return; }
            FirebaseAuth.getInstance().signInWithEmailAndPassword(e, p).addOnSuccessListener(result -> {
                if (FirebaseAuth.getInstance().getCurrentUser() == null) { toast("Login failed"); return; }
                FirebaseAuth.getInstance().getCurrentUser().getIdToken(true).addOnSuccessListener(token -> {
                    if (Boolean.TRUE.equals(token.getClaims().get("admin"))) {
                        startActivity(new android.content.Intent(this, MessageActivity.class)); finish();
                    } else { FirebaseAuth.getInstance().signOut(); toast("Admin access required"); }
                });
            }).addOnFailureListener(error -> toast("Login failed: " + error.getMessage()));
        });
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}

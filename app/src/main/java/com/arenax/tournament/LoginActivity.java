package com.arenax.tournament;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.arenax.tournament.data.FirebaseRepository;
import com.arenax.tournament.util.InputValidation;
import com.arenax.tournament.util.SecureCredentialStore;
import com.google.firebase.auth.FirebaseAuth;

public class LoginActivity extends AppCompatActivity {
    private FirebaseAuth auth;
    private EditText nameInput, emailInput, phoneInput, passwordInput;
    private CheckBox rememberInput;
    private TextView title, subtitle, tabLogin, tabRegister, forgot;
    private Button primaryButton;
    private boolean registrationMode;
    private ValueAnimator subtitleRgbAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // This activity is now the launcher (the old splash screen was removed
        // because it was crashing on some devices). Do the routing it used to
        // do first, but keep it wrapped so a Firebase/config problem can never
        // take down the launcher boundary again.
        if ("admin".equals(BuildConfig.FLAVOR)) {
            startActivity(new Intent(this, AdminLoginActivity.class));
            finish();
            return;
        }
        if ("messenger".equals(BuildConfig.FLAVOR)) {
            startActivity(new Intent(this, MessageLoginActivity.class));
            finish();
            return;
        }
        try {
            buildPremiumLoginScreen();
        } catch (Throwable error) {
            // Never let a decorative resource or theme issue close the app.
            buildFallbackLoginScreen();
        }
    }

    /** Minimal, dependency-free screen used only if the styled layout fails to inflate. */
    private void buildFallbackLoginScreen() {
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(10, 10, 13));

        TextView titleView = new TextView(this);
        titleView.setText("STARX24 LOGIN");
        titleView.setTextColor(Color.rgb(255, 59, 78));
        titleView.setTextSize(22);
        titleView.setGravity(android.view.Gravity.CENTER);
        root.addView(titleView);

        emailInput = new EditText(this);
        emailInput.setHint("Email address");
        emailInput.setTextColor(Color.WHITE);
        root.addView(emailInput);

        passwordInput = new EditText(this);
        passwordInput.setHint("Password");
        passwordInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setTextColor(Color.WHITE);
        root.addView(passwordInput);

        Button login = new Button(this);
        login.setText("SIGN IN");
        login.setOnClickListener(v -> signIn(login));
        root.addView(login);

        setContentView(root);
    }

    private void buildPremiumLoginScreen() {
        setContentView(R.layout.activity_login);

        title = findViewById(R.id.login_title);
        subtitle = findViewById(R.id.login_subtitle);
        tabLogin = findViewById(R.id.tab_login);
        tabRegister = findViewById(R.id.tab_register);
        nameInput = findViewById(R.id.input_name);
        emailInput = findViewById(R.id.input_email);
        phoneInput = findViewById(R.id.input_phone);
        passwordInput = findViewById(R.id.input_password);
        rememberInput = findViewById(R.id.check_remember);
        forgot = findViewById(R.id.text_forgot);
        primaryButton = findViewById(R.id.btn_primary);

        emailInput.setText(getSharedPreferences("arenax_auth", MODE_PRIVATE).getString("email", ""));
        passwordInput.setText(SecureCredentialStore.loadPassword(this));

        tabLogin.setOnClickListener(v -> { if (registrationMode) toggleMode(); });
        tabRegister.setOnClickListener(v -> { if (!registrationMode) toggleMode(); });

        primaryButton.setOnClickListener(v -> {
            if (registrationMode) register(primaryButton);
            else signIn(primaryButton);
        });
        forgot.setOnClickListener(v -> resetPassword());

        setStyledTitle(title, "welcome back");
        startSubtitleRgbAnimation(subtitle);
    }

    /** Lowercases the title and colors its last word blood red, rest stays default. */
    private void setStyledTitle(TextView target, String lowerCaseText) {
        SpannableString styled = new SpannableString(lowerCaseText);
        int lastWordStart = lowerCaseText.lastIndexOf(' ') + 1;
        styled.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.blood_red)),
                lastWordStart, lowerCaseText.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        target.setText(styled);
    }

    /** Animates a moving white -> red -> orange gradient across the subtitle text. */
    private void startSubtitleRgbAnimation(TextView target) {
        target.post(() -> {
            int width = target.getWidth();
            if (width <= 0) return;
            int[] colors = {Color.WHITE, Color.RED, Color.rgb(255, 140, 0), Color.WHITE};
            LinearGradient gradient = new LinearGradient(
                    0, 0, width * 2f, 0, colors, null, Shader.TileMode.MIRROR);
            target.getPaint().setShader(gradient);

            if (subtitleRgbAnimator != null) subtitleRgbAnimator.cancel();
            subtitleRgbAnimator = ValueAnimator.ofFloat(0f, width * 2f);
            subtitleRgbAnimator.setDuration(2400);
            subtitleRgbAnimator.setRepeatCount(ValueAnimator.INFINITE);
            subtitleRgbAnimator.setInterpolator(new LinearInterpolator());
            subtitleRgbAnimator.addUpdateListener(animation -> {
                float translate = (float) animation.getAnimatedValue();
                android.graphics.Matrix matrix = new android.graphics.Matrix();
                matrix.setTranslate(translate, 0);
                gradient.setLocalMatrix(matrix);
                target.invalidate();
            });
            subtitleRgbAnimator.start();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (subtitleRgbAnimator != null) subtitleRgbAnimator.cancel();
    }

    private void toggleMode() {
        registrationMode = !registrationMode;
        if (registrationMode) {
            setStyledTitle(title, "create account");
            subtitle.setText("Join the arena and start competing");
            nameInput.setVisibility(View.VISIBLE);
            phoneInput.setVisibility(View.VISIBLE);
            primaryButton.setText("CREATE ACCOUNT   →");
            forgot.setVisibility(View.GONE);
            tabLogin.setBackgroundResource(R.drawable.bg_login_tab_inactive);
            tabLogin.setTextColor(getResources().getColor(R.color.text_secondary));
            tabRegister.setBackgroundResource(R.drawable.bg_login_tab_active);
            tabRegister.setTextColor(getResources().getColor(R.color.blood_red));
        } else {
            setStyledTitle(title, "welcome back");
            subtitle.setText("Sign in to continue your tournament run");
            nameInput.setVisibility(View.GONE);
            phoneInput.setVisibility(View.GONE);
            primaryButton.setText("LOGIN   →");
            forgot.setVisibility(View.VISIBLE);
            tabRegister.setBackgroundResource(R.drawable.bg_login_tab_inactive);
            tabRegister.setTextColor(getResources().getColor(R.color.text_secondary));
            tabLogin.setBackgroundResource(R.drawable.bg_login_tab_active);
            tabLogin.setTextColor(getResources().getColor(R.color.blood_red));
        }
    }

    private void signIn(Button button) {
        if (registrationMode) return;
        if (!ensureFirebase()) return;
        String email = value(emailInput), pass = value(passwordInput);
        if (!InputValidation.email(emailInput) || !InputValidation.password(passwordInput)) {
            toast("Check your email and password");
            return;
        }
        button.setEnabled(false);
        auth.signInWithEmailAndPassword(email, pass).addOnSuccessListener(result -> {
            getSharedPreferences("arenax_auth", MODE_PRIVATE).edit().putString("email", rememberInput.isChecked() ? email : "").apply();
            if (rememberInput.isChecked()) SecureCredentialStore.savePassword(this, pass);
            else SecureCredentialStore.clear(this);
            openApp();
        }).addOnFailureListener(error -> { button.setEnabled(true); toast(message(error)); });
    }

    private void register(Button button) {
        if (!ensureFirebase()) return;
        String name = value(nameInput), email = value(emailInput), phone = value(phoneInput).replaceAll("\\D", ""), pass = value(passwordInput);
        if (!InputValidation.length(nameInput, 2, 60, "Enter your name")
                || !InputValidation.email(emailInput)
                || !InputValidation.phone(phoneInput)
                || !InputValidation.password(passwordInput)) {
            toast("Please correct the highlighted fields");
            return;
        }
        button.setEnabled(false);
        auth.createUserWithEmailAndPassword(email, pass).addOnSuccessListener(result -> {
            if (result.getUser() == null) { button.setEnabled(true); return; }
            String uid = result.getUser().getUid();
            FirebaseRepository.reservePhone(phone, uid)
                    .addOnSuccessListener(v -> FirebaseRepository.saveUserProfile(uid, email, name, phone)
                            .addOnSuccessListener(done -> openApp())
                            .addOnFailureListener(error -> { button.setEnabled(true); auth.signOut(); toast(message(error)); }))
                    .addOnFailureListener(error -> {
                        button.setEnabled(true);
                        auth.signOut();
                        toast("This mobile number is already registered.");
                    });
        }).addOnFailureListener(error -> { button.setEnabled(true); toast(message(error)); });
    }

    private void resetPassword() {
        if (!ensureFirebase()) return;
        String email = value(emailInput);
        if (email.isEmpty()) { toast("Enter your email first"); return; }
        auth.sendPasswordResetEmail(email).addOnSuccessListener(v -> toast("Password reset email sent")).addOnFailureListener(e -> toast(message(e)));
    }

    private boolean ensureFirebase() {
        try {
            if (auth == null && FirebaseRepository.isFirebaseAvailable(this)) auth = FirebaseAuth.getInstance();
        } catch (Throwable ignored) { auth = null; }
        if (auth != null) return true;
        toast("Login service is unavailable. Please check Firebase setup.");
        return false;
    }

    private void openApp() { startActivity(new Intent(this, MainActivity.class)); finish(); }
    private String value(EditText input) { return input.getText() == null ? "" : input.getText().toString().trim(); }
    private String message(Exception error) { return error.getMessage() == null ? "Please try again" : error.getMessage(); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}

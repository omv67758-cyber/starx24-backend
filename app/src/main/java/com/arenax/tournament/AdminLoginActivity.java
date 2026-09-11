package com.arenax.tournament;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.arenax.tournament.data.FirebaseRepository;
import com.arenax.tournament.util.InputValidation;
import com.arenax.tournament.util.SecureCredentialStore;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * Admin login deliberately uses only platform views (no XML layout, no drawable
 * resources). This avoids a crash while moving from the launcher into a
 * Material/XML login layout on devices where the theme or generated Firebase
 * resources are not ready yet. All the STARX24 "glass card" styling below is
 * therefore built with GradientDrawable/LayerDrawable in code rather than
 * loaded from res/drawable, so it carries zero resource-load risk.
 */
public class AdminLoginActivity extends Activity {
    private static final String BOOTSTRAP_MASTER_EMAIL = "fflueclark@gmail.com";
    private static final String PREFS = "arenax_admin_login_saved";
    private static final String KEY_EMAIL = "email";
    private static final String KEY_REMEMBER_EMAIL = "remember_email";
    private static final int MAX_LOGIN_FAILURES = 5;
    private static final long LOCKOUT_MS = 30_000L;

    // Palette matched to res/values/colors.xml so the admin screen reads as
    // the same product as the user login/register screens.
    private static final int SCREEN_DARK_RED_BASE = Color.parseColor("#280000");
    private static final int SCREEN_DARK_RED_MID = Color.parseColor("#3A0000");
    private static final int GLASS_FILL = Color.parseColor("#80200008");
    private static final int GLASS_SHEEN_WHITE = Color.parseColor("#22FFFFFF");
    private static final int GLASS_SHEEN_SOFT = Color.parseColor("#10FFFFFF");
    private static final int BLOOD_RED = Color.parseColor("#B31217");
    private static final int TEXT_PRIMARY = Color.parseColor("#F7F8FA");
    private static final int TEXT_SECONDARY = Color.parseColor("#A6ABB8");
    private static final int TEXT_MUTED = Color.parseColor("#8A919C");
    private static final int INPUT_FILL = Color.parseColor("#B3190C10");
    private static final int BTN_GLASS_FILL = Color.parseColor("#8CB31217");
    private static final int BTN_SHEEN_RED = Color.parseColor("#59FF3B3B");
    private static final int RIPPLE_BLOOD = Color.parseColor("#66B31217");
    private static final int WARNING_ORANGE = Color.parseColor("#F0B429");

    private FirebaseAuth auth;
    private boolean firebaseAvailable;
    private EditText emailInput;
    private EditText passwordInput;
    private Button loginButton;
    private CheckBox rememberEmail;
    private TextView passwordToggle;
    private boolean passwordVisible;
    private int failedAttempts;
    private long lockedUntil;
    private ValueAnimator subtitleRgbAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            buildLoginScreen();
            initializeFirebaseSafely();
        } catch (Throwable error) {
            firebaseAvailable = false;
            buildFallbackScreen();
            Toast.makeText(this, "Admin login screen loaded. Check Firebase setup.", Toast.LENGTH_LONG).show();
        }
    }

    private void buildLoginScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(loginBackground());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(18), dp(24), dp(18), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -1));

        TextView back = label("‹  BACK", 12, TEXT_SECONDARY, true);
        back.setPadding(0, 0, 0, dp(14));
        root.addView(back, params(-1, dp(46), 0));
        back.setOnClickListener(v -> finish());

        // Shadow wrapper: a hand-drawn soft shadow behind the glass card,
        // same technique as the user login/register screens.
        FrameLayout shadowWrap = new FrameLayout(this);
        shadowWrap.setBackground(cardShadowDrawable());
        shadowWrap.setPadding(dp(8), dp(10), dp(8), dp(20));
        root.addView(shadowWrap, params(-1, -2, 0));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(glassPanelDrawable());
        card.setPadding(dp(20), dp(26), dp(20), dp(24));
        shadowWrap.addView(card, new FrameLayout.LayoutParams(-1, -2));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.starx24_logo);
        logo.setContentDescription(getString(R.string.app_name));
        card.addView(logo, params(dp(76), dp(76), 0));
        ((LinearLayout.LayoutParams) logo.getLayoutParams()).gravity = Gravity.CENTER_HORIZONTAL;

        String flavor = BuildConfig.FLAVOR;
        String appTitle = "paymentAdmin".equals(flavor) ? "PAYMENT DESK" : "matchAdmin".equals(flavor) ? "MATCH OPS" : "masterControl".equals(flavor) ? "MASTER CONTROL" : "STARX24";
        int accent = "paymentAdmin".equals(flavor) ? Color.rgb(36, 176, 112) : "matchAdmin".equals(flavor) ? Color.rgb(230, 141, 42) : "masterControl".equals(flavor) ? Color.rgb(179, 18, 23) : BLOOD_RED;
        TextView brand = label(appTitle, 18, accent, true);
        brand.setGravity(Gravity.CENTER);
        brand.setLetterSpacing(0.16f);
        card.addView(brand, params(-1, -2, dp(10)));

        TextView title = new TextView(this);
        setStyledTitle(title, "masterControl".equals(flavor) ? "control center" : "paymentAdmin".equals(flavor) ? "payment admin" : "matchAdmin".equals(flavor) ? "match admin" : "admin panel");
        title.setTextSize(26);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(title, params(-1, -2, dp(18)));

        TextView subtitle = label("masterControl".equals(flavor) ? "Gmail authentication required for master access" : "paymentAdmin".equals(flavor) ? "Secure withdrawal operations" : "matchAdmin".equals(flavor) ? "Tournament content and room operations" : "Sign in with your authorized admin account", 13, TEXT_SECONDARY, false);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(subtitle, params(-1, -2, dp(4)));
        startSubtitleRgbAnimation(subtitle);

        emailInput = field("Gmail address", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        passwordInput = field("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        loadSavedCredentials();
        card.addView(emailInput, params(-1, dp(58), dp(22)));
        card.addView(passwordInput, params(-1, dp(58), dp(12)));

        LinearLayout passwordActions = new LinearLayout(this);
        passwordActions.setGravity(Gravity.CENTER_VERTICAL);
        passwordActions.setPadding(dp(4), 0, dp(4), 0);
        passwordToggle = label("SHOW PASSWORD", 11, BLOOD_RED, true);
        passwordToggle.setGravity(Gravity.CENTER_VERTICAL);
        passwordToggle.setOnClickListener(v -> togglePasswordVisibility());
        passwordActions.addView(passwordToggle, new LinearLayout.LayoutParams(0, dp(36), 1));
        TextView forgot = label("FORGOT PASSWORD?", 11, WARNING_ORANGE, true);
        forgot.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        forgot.setOnClickListener(v -> sendPasswordReset());
        passwordActions.addView(forgot, new LinearLayout.LayoutParams(0, dp(36), 1));
        card.addView(passwordActions, params(-1, dp(38), dp(2)));

        rememberEmail = new CheckBox(this);
        rememberEmail.setText("Remember email on this device");
        rememberEmail.setTextColor(TEXT_SECONDARY);
        rememberEmail.setTextSize(12);
        rememberEmail.setButtonTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{BLOOD_RED, TEXT_SECONDARY}));
        rememberEmail.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_REMEMBER_EMAIL, true));
        card.addView(rememberEmail, params(-1, dp(42), dp(2)));

        loginButton = new Button(this);
        loginButton.setText("LOGIN  →");
        loginButton.setTextColor(Color.WHITE);
        loginButton.setTextSize(14);
        loginButton.setTypeface(null, Typeface.BOLD);
        loginButton.setAllCaps(false);
        loginButton.setBackground(primaryButtonDrawable());
        loginButton.setForeground(new RippleDrawable(ColorStateList.valueOf(RIPPLE_BLOOD), null, null));
        applyRoundOutline(loginButton, 11);
        loginButton.setElevation(dp(6));
        card.addView(loginButton, params(-1, dp(56), dp(22)));
        loginButton.setOnClickListener(v -> signIn());

        TextView note = label("Only the authorized admin account can enter.", 11, TEXT_MUTED, false);
        note.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(note, params(-1, -2, dp(16)));

        setContentView(scroll);
    }

    /** Lowercases the title and colors its last word blood red, matching the login/register screens. */
    private void setStyledTitle(TextView target, String lowerCaseText) {
        SpannableString styled = new SpannableString(lowerCaseText);
        int lastWordStart = lowerCaseText.lastIndexOf(' ') + 1;
        styled.setSpan(new ForegroundColorSpan(BLOOD_RED),
                lastWordStart, lowerCaseText.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        target.setText(styled);
        target.setTextColor(TEXT_PRIMARY);
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
                Matrix matrix = new Matrix();
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

    private void buildFallbackScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        // Plain color only here: this is the fallback for when the fancier
        // background failed above, so it must never depend on a resource
        // that could fail to load in the same way.
        root.setBackgroundColor(Color.rgb(20, 8, 10));
        TextView message = label("ADMIN LOGIN\n\nFirebase configuration is unavailable.", 18, TEXT_PRIMARY, true);
        message.setGravity(Gravity.CENTER);
        root.addView(message, params(-1, -2, 0));
        Button back = new Button(this);
        back.setText("BACK");
        back.setOnClickListener(v -> finish());
        root.addView(back, params(-1, dp(54), dp(24)));
        setContentView(root);
    }

    private void initializeFirebaseSafely() {
        firebaseAvailable = false;
        try {
            firebaseAvailable = FirebaseRepository.isFirebaseAvailable(this);
            if (firebaseAvailable) auth = FirebaseAuth.getInstance();
            // Always keep the login page visible. The saved fields are loaded
            // above, and the user explicitly taps LOGIN to continue.
            // Do not auto-redirect based on an old Firebase session.
        } catch (Throwable ignored) {
            auth = null;
            firebaseAvailable = false;
        }
    }

    private void loadSavedCredentials() {
        SharedPreferences saved = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (saved.getBoolean(KEY_REMEMBER_EMAIL, true)) {
            emailInput.setText(saved.getString(KEY_EMAIL, ""));
            passwordInput.setText(SecureCredentialStore.loadPassword(this));
        }
    }

    private void saveCredentials(String email, String password) {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        // Never persist an admin password on the device. Firebase Auth keeps
        // the authenticated session; only the convenience email is optional.
        boolean remember = rememberEmail == null || rememberEmail.isChecked();
        editor.putBoolean(KEY_REMEMBER_EMAIL, remember);
        if (remember) editor.putString(KEY_EMAIL, email);
        else editor.remove(KEY_EMAIL);
        editor.apply();
        if (remember) SecureCredentialStore.savePassword(this, password);
        else SecureCredentialStore.clear(this);
    }

    private void signIn() {
        if (System.currentTimeMillis() < lockedUntil) {
            long seconds = Math.max(1L, (lockedUntil - System.currentTimeMillis() + 999L) / 1000L);
            toast("Too many failed attempts. Try again in " + seconds + " seconds.");
            return;
        }
        if (!firebaseAvailable || auth == null) {
            toast("Firebase configuration is unavailable. Check google-services.json.");
            return;
        }
        String email = emailInput.getText() == null ? "" : emailInput.getText().toString().trim();
        String password = passwordInput.getText() == null ? "" : passwordInput.getText().toString();
        if ("masterControl".equals(BuildConfig.FLAVOR) && !email.toLowerCase(java.util.Locale.US).endsWith("@gmail.com")) {
            toast("Master Control accepts Gmail accounts only.");
            return;
        }
        if (!InputValidation.email(emailInput) || !InputValidation.password(passwordInput)) {
            toast("Check the highlighted admin login fields");
            return;
        }
        saveCredentials(email, password);
        loginButton.setEnabled(false);
        try {
            auth.signInWithEmailAndPassword(email, password)
                    .addOnSuccessListener(result -> verifyAdminEmail())
                    .addOnFailureListener(error -> {
                        loginButton.setEnabled(true);
                        failedAttempts++;
                        if (failedAttempts >= MAX_LOGIN_FAILURES) {
                            lockedUntil = System.currentTimeMillis() + LOCKOUT_MS;
                            failedAttempts = 0;
                        }
                        toast(error.getMessage() == null ? "Admin login failed." : error.getMessage());
                    });
        } catch (Throwable error) {
            loginButton.setEnabled(true);
            toast("Admin login could not start. Please try again.");
        }
    }

    private void verifyAdminEmail() {
        loginButton.setEnabled(true);
        failedAttempts = 0;
        FirebaseUser user = auth == null ? null : auth.getCurrentUser();
        if (user == null) {
            toast("This account is not authorized for admin access.");
            return;
        }
        // Roles are provisioned in adminUsers/{uid}; do not require a separate
        // custom Firebase Auth claim, because that claim is not part of the
        // supplied Firebase setup and would reject valid admin accounts.
        if ("masterControl".equals(BuildConfig.FLAVOR)
                && (user.getEmail() == null || !user.getEmail().toLowerCase(java.util.Locale.US).endsWith("@gmail.com"))) {
            auth.signOut();
            toast("Master Control accepts Gmail accounts only.");
            return;
        }
        resolveRoleAndOpen(user.getUid());
    }

    private void togglePasswordVisibility() {
        passwordVisible = !passwordVisible;
        int cursor = passwordInput.getSelectionStart();
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT
                | (passwordVisible ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_TEXT_VARIATION_PASSWORD));
        passwordInput.setSelection(Math.max(0, cursor));
        passwordToggle.setText(passwordVisible ? "HIDE PASSWORD" : "SHOW PASSWORD");
    }

    private void sendPasswordReset() {
        if (!firebaseAvailable || auth == null) {
            toast("Firebase configuration is unavailable. Check google-services.json.");
            return;
        }
        String email = emailInput.getText() == null ? "" : emailInput.getText().toString().trim();
        if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            toast("Enter a valid admin email first.");
            return;
        }
        auth.sendPasswordResetEmail(email)
                .addOnSuccessListener(unused -> toast("Password reset email sent if this account exists."))
                .addOnFailureListener(error -> toast("Could not send password reset email."));
    }

    /** A1 RBAC — first admin ever to sign in with no role record is bootstrapped as SUPER_ADMIN;
     *  every admin after that must be assigned a role from the Roles page by an existing Super Admin. */
    private void resolveRoleAndOpen(String uid) {
        String signedInEmail = auth == null || auth.getCurrentUser() == null ? "" : auth.getCurrentUser().getEmail();
        if ("masterControl".equals(BuildConfig.FLAVOR) && BOOTSTRAP_MASTER_EMAIL.equalsIgnoreCase(signedInEmail)) {
            FirebaseRepository.bootstrapMaster(uid, signedInEmail)
                    .addOnSuccessListener(unused -> openAdmin("MASTER_CONTROL"))
                    .addOnFailureListener(error -> {
                        // The backend/rules may already contain the record; allow
                        // entry so an existing provisioned Master is not locked out.
                        openAdmin("MASTER_CONTROL");
                    });
            return;
        }
        FirebaseRepository.adminUsers().child(uid).get()
                .addOnSuccessListener(snapshot -> {
                    if (snapshot.exists()) {
                        boolean enabled = Boolean.TRUE.equals(snapshot.child("enabled").getValue(Boolean.class));
                        String status = String.valueOf(snapshot.child("status").getValue());
                        if (!enabled || !"ACTIVE".equals(status)) { FirebaseAuth.getInstance().signOut(); toast("This admin account is disabled or revoked."); return; }
                        openAdmin(String.valueOf(snapshot.child("role").getValue()));
                    } else {
                        FirebaseRepository.adminRoles().child(uid).get().addOnSuccessListener(legacy -> {
                            if (legacy.exists()) openAdmin(String.valueOf(legacy.child("role").getValue()));
                            else { FirebaseAuth.getInstance().signOut(); toast("Admin access has not been provisioned."); }
                        }).addOnFailureListener(error -> { FirebaseAuth.getInstance().signOut(); toast("Could not validate admin access."); });
                    }
                })
                .addOnFailureListener(error -> { FirebaseAuth.getInstance().signOut(); toast("Could not validate admin access."); });
    }

    private void openAdmin(String role) {
        try {
            Class<?> destination;
            String flavor = BuildConfig.FLAVOR;
            if ("paymentAdmin".equals(flavor)) {
                if (!"PAYMENT_ADMIN".equals(role)) { toast("Payment Admin role required."); return; }
                destination = PaymentAdminActivity.class;
            } else if ("matchAdmin".equals(flavor)) {
                if (!"MATCH_ADMIN".equals(role)) { toast("Match Admin role required."); return; }
                destination = MatchAdminActivity.class;
            } else if ("masterControl".equals(flavor)) {
                if (!"MASTER_CONTROL".equals(role) && !"SUPER_ADMIN".equals(role)) { toast("Master Control role required."); return; }
                destination = MasterControlActivity.class;
            } else {
                destination = AdminActivity.class;
            }
            Intent intent = new Intent(this, destination);
            intent.putExtra("adminRole", role);
            startActivity(intent);
            finish();
        } catch (Throwable error) {
            toast("Admin dashboard could not open. Please try again.");
        }
    }

    private EditText field(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setTextColor(TEXT_PRIMARY);
        field.setHintTextColor(TEXT_SECONDARY);
        field.setTextSize(14);
        field.setSingleLine(true);
        field.setInputType(inputType);
        field.setPadding(dp(16), 0, dp(16), 0);
        field.setBackground(inputFieldDrawable());
        applyRoundOutline(field, 10);
        field.setElevation(dp(5));
        return field;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private LinearLayout.LayoutParams params(int width, int height, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.topMargin = top;
        return p;
    }

    /** Ensures the native elevation shadow follows the view's rounded-rect shape, not a plain rectangle. */
    private void applyRoundOutline(View view, int radiusDp) {
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(radiusDp));
            }
        });
        view.setClipToOutline(false);
    }

    /** No-outline input fill: dark by default, blood-red bordered ring only while focused. */
    private StateListDrawable inputFieldDrawable() {
        StateListDrawable states = new StateListDrawable();

        GradientDrawable focused = new GradientDrawable();
        focused.setColor(Color.parseColor("#B31C1319"));
        focused.setCornerRadius(dp(10));
        focused.setStroke((int) (1.4f * getResources().getDisplayMetrics().density), BLOOD_RED);
        states.addState(new int[]{android.R.attr.state_focused}, focused);

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(INPUT_FILL);
        normal.setCornerRadius(dp(10));
        states.addState(new int[]{}, normal);

        return states;
    }

    /** Same layered glass look as bg_login_button_primary.xml, built in code. */
    private Drawable primaryButtonDrawable() {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(BTN_GLASS_FILL);
        fill.setCornerRadius(dp(11));
        fill.setStroke((int) (1.4f * getResources().getDisplayMetrics().density), BLOOD_RED);

        GradientDrawable sheen = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{BTN_SHEEN_RED, Color.TRANSPARENT});
        sheen.setCornerRadius(dp(11));

        return new LayerDrawable(new Drawable[]{fill, sheen});
    }

    /** Same translucent glass card look as bg_login_glass_panel.xml, built in code. */
    private Drawable glassPanelDrawable() {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(GLASS_FILL);
        fill.setCornerRadius(dp(18));

        GradientDrawable sheen = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{GLASS_SHEEN_WHITE, GLASS_SHEEN_SOFT, Color.TRANSPARENT});
        sheen.setCornerRadius(dp(18));

        GradientDrawable depth = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.TRANSPARENT, Color.parseColor("#22000000")});
        depth.setCornerRadius(dp(18));

        GradientDrawable redGlow = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.TRANSPARENT, Color.parseColor("#2EB31217")});
        redGlow.setCornerRadius(dp(18));

        return new LayerDrawable(new Drawable[]{fill, sheen, depth, redGlow});
    }

    /** Soft, layered grey drop shadow behind the card -- same technique as
     *  bg_card_shadow.xml on the user login/register screens, built in code
     *  so it needs no drawable resource. */
    private Drawable cardShadowDrawable() {
        int[] alphas = {0x16, 0x12, 0x0D};
        int[] radii = {28, 26, 24};
        int[] insets = {0, 4, 8};

        Drawable[] layers = new Drawable[alphas.length];
        for (int i = 0; i < alphas.length; i++) {
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(Color.argb(alphas[i], 0, 0, 0));
            shape.setCornerRadius(dp(radii[i]));
            layers[i] = shape;
        }
        LayerDrawable layered = new LayerDrawable(layers);
        for (int i = 0; i < insets.length; i++) {
            int side = dp(insets[i]);
            int bottom = dp((int) (insets[i] * 1.6));
            layered.setLayerInset(i, side, (int) (side * 0.75f), side, bottom);
        }
        return layered;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Built in code instead of loaded from a drawable resource so it can
     * never throw Resources.NotFoundException at the launcher boundary,
     * regardless of what the packaging toolchain does with XML drawables.
     * Matches the dark red screen used on the user login/register screens.
     */
    private Drawable loginBackground() {
        GradientDrawable base = new GradientDrawable();
        base.setColor(SCREEN_DARK_RED_BASE);

        GradientDrawable diagonalWash = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{SCREEN_DARK_RED_MID, SCREEN_DARK_RED_BASE, Color.parseColor("#0F0000")});

        GradientDrawable topGlow = new GradientDrawable();
        topGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        topGlow.setGradientRadius(dp(480));
        topGlow.setGradientCenter(0.5f, 0.05f);
        topGlow.setColors(new int[]{Color.parseColor("#4DB31217"), Color.parseColor("#00000000")});

        GradientDrawable bottomGlow = new GradientDrawable();
        bottomGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        bottomGlow.setGradientRadius(dp(400));
        bottomGlow.setGradientCenter(0.5f, 1.0f);
        bottomGlow.setColors(new int[]{Color.parseColor("#337A0000"), Color.parseColor("#00000000")});

        return new LayerDrawable(new Drawable[]{base, diagonalWash, topGlow, bottomGlow});
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}

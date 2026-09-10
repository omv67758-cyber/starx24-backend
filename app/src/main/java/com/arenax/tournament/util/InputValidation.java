package com.arenax.tournament.util;

import android.util.Patterns;
import android.widget.EditText;

import java.util.regex.Pattern;

/**
 * Small, dependency-free validation helpers shared by the authentication and
 * user-generated-content screens. Keeping the rules in one place prevents
 * individual dialogs from accepting values that Firebase rules or later
 * screens cannot safely handle.
 */
public final class InputValidation {
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]{3,64}$");

    private InputValidation() {}

    public static String value(EditText input) {
        return input == null || input.getText() == null
                ? ""
                : input.getText().toString().trim();
    }

    public static boolean required(EditText input, String message) {
        if (!value(input).isEmpty()) return true;
        if (input != null) {
            input.setError(message);
            input.requestFocus();
        }
        return false;
    }

    public static boolean email(EditText input) {
        String email = value(input);
        if (Patterns.EMAIL_ADDRESS.matcher(email).matches()) return true;
        if (input != null) {
            input.setError("Enter a valid email address");
            input.requestFocus();
        }
        return false;
    }

    public static boolean password(EditText input) {
        if (value(input).length() >= 6) return true;
        if (input != null) {
            input.setError("Password must be at least 6 characters");
            input.requestFocus();
        }
        return false;
    }

    public static boolean phone(EditText input) {
        String phone = value(input).replaceAll("\\D", "");
        if (phone.length() == 10) return true;
        if (input != null) {
            input.setError("Enter a valid 10-digit mobile number");
            input.requestFocus();
        }
        return false;
    }

    public static boolean length(EditText input, int min, int max, String message) {
        int length = value(input).length();
        if (length >= min && length <= max) return true;
        if (input != null) {
            input.setError(message);
            input.requestFocus();
        }
        return false;
    }

    public static boolean safeId(EditText input, String message) {
        if (SAFE_ID.matcher(value(input)).matches()) return true;
        if (input != null) {
            input.setError(message);
            input.requestFocus();
        }
        return false;
    }
}
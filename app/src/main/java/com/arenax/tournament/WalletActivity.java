package com.arenax.tournament;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.arenax.tournament.data.FirebaseRepository;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

public class WalletActivity extends AppCompatActivity {
    private long balance;
    private TextView total, breakdown;
    private ValueEventListener balanceListener;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_wallet);
        total = findViewById(R.id.wallet_total);
        breakdown = findViewById(R.id.wallet_breakdown);
        render();
        loadWalletBalance();
        findViewById(R.id.wallet_back).setOnClickListener(v -> finish());
        findViewById(R.id.wallet_add_10).setOnClickListener(v -> startPayment(10));
        findViewById(R.id.wallet_add_50).setOnClickListener(v -> startPayment(50));
        findViewById(R.id.wallet_add_100).setOnClickListener(v -> startPayment(100));
        findViewById(R.id.wallet_add_money).setOnClickListener(v -> {
            EditText input = findViewById(R.id.wallet_custom_amount);
            try {
                int amount = Integer.parseInt(input.getText().toString().trim());
                if (amount > 0) startPayment(amount); else toast("Enter a valid amount");
            } catch (Exception e) { toast("Enter a valid amount"); }
        });
        findViewById(R.id.wallet_withdraw).setOnClickListener(v ->
                toast("Withdrawal will be enabled after verified payout setup."));
    }

    private void startPayment(int amount) {
        if (amount < 1 || amount > 100000) {
            toast("Enter an amount between ₹1 and ₹1,00,000");
            return;
        }
        toast("Creating secure payment order...");
        String mobile = getSharedPreferences("profile", MODE_PRIVATE).getString("phone", "");
        ZapUpiClient.createOrder(amount, mobile, new ZapUpiClient.Callback() {
            @Override public void onSuccess(String paymentUrl, String orderId) {
                new androidx.appcompat.app.AlertDialog.Builder(WalletActivity.this)
                        .setTitle("Continue to ZapUPI")
                        .setMessage("Order created: " + orderId + "\nComplete the payment on the secure ZapUPI page. Your wallet updates after the server verifies the payment.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("OPEN PAYMENT", (dialog, which) -> {
                            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(paymentUrl))); }
                            catch (Exception e) { toast("Could not open payment page"); }
                        }).show();
            }
            @Override public void onError(String message) { toast(message); }
        });
    }

    private void loadWalletBalance() {
        com.google.firebase.auth.FirebaseUser user = FirebaseRepository.currentUser();
        if (user == null) return;

        balanceListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                Long value = snapshot.getValue(Long.class);
                balance = value == null ? 0L : value;
                render();
            }

            @Override public void onCancelled(DatabaseError error) {
                toast("Could not load wallet balance.");
            }
        };
        FirebaseRepository.users().child(user.getUid()).child("wallet").child("balance")
                .addValueEventListener(balanceListener);
    }

    @Override protected void onDestroy() {
        com.google.firebase.auth.FirebaseUser user = FirebaseRepository.currentUser();
        if (user != null && balanceListener != null) {
            FirebaseRepository.users().child(user.getUid()).child("wallet").child("balance")
                    .removeEventListener(balanceListener);
        }
        super.onDestroy();
    }

    private void render() {
        total.setText("🪙  " + balance);
        breakdown.setText("Deposit Balance  🪙 0     •     Winning Balance  🪙 " + balance);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}

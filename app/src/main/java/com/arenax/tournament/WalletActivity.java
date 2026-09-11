package com.arenax.tournament;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.browser.customtabs.CustomTabsServiceConnection;
import androidx.browser.customtabs.CustomTabsSession;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.arenax.tournament.adapter.TransactionAdapter;
import com.arenax.tournament.data.FirebaseRepository;
import com.arenax.tournament.model.Transaction;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;

public class WalletActivity extends AppCompatActivity {

    private long balance;
    private TextView total, depositAmount, winningAmount;
    private TextView amount10, amount20, amount30, amount40, amount50,
            amount60, amount70, amount80, amount90, amount100, selectedChip;
    private TextView[] amountChips;
    private EditText customAmountInput;
    private View emptyState;
    private RecyclerView historyRecycler;
    private final List<Transaction> transactions = new ArrayList<>();
    private TransactionAdapter transactionAdapter;

    private ValueEventListener balanceListener;
    private ValueEventListener historyListener;
    private CustomTabsSession customTabsSession;
    private CustomTabsServiceConnection customTabsConnection;

    private static final String WALLET_PREFS = "wallet";
    private static final String PREF_PENDING_ORDER_ID = "pending_order_id";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_wallet);

        // Fire immediately, before anything else, so the backend and Firebase token
        // have the most possible time to warm up before the user taps "Add Money".
        ZapUpiClient.warmUp();
        warmUpCustomTabs();

        bindViews();
        setupAmountChips();
        setupHistoryList();

        render();
        loadWalletBalance();
        loadTransactionHistory();

        findViewById(R.id.wallet_back).setOnClickListener(v -> finish());

        findViewById(R.id.wallet_add_money).setOnClickListener(v -> {
            String raw = customAmountInput.getText().toString().trim();
            if (!raw.isEmpty()) {
                try {
                    int amount = (int) Double.parseDouble(raw);
                    startPayment(amount);
                } catch (Exception e) {
                    toast(getString(R.string.wallet_invalid_amount));
                }
            } else if (selectedChip != null) {
                startPayment(Integer.parseInt(selectedChip.getText().toString().replace("₹", "")));
            } else {
                toast(getString(R.string.wallet_invalid_amount));
            }
        });

        findViewById(R.id.wallet_withdraw).setOnClickListener(v ->
                toast(getString(R.string.wallet_withdraw_disabled)));
    }

    private void bindViews() {
        total = findViewById(R.id.wallet_total);
        depositAmount = findViewById(R.id.wallet_deposit_amount);
        winningAmount = findViewById(R.id.wallet_winning_amount);
        amount10 = findViewById(R.id.wallet_add_10);
        amount20 = findViewById(R.id.wallet_add_20);
        amount30 = findViewById(R.id.wallet_add_30);
        amount40 = findViewById(R.id.wallet_add_40);
        amount50 = findViewById(R.id.wallet_add_50);
        amount60 = findViewById(R.id.wallet_add_60);
        amount70 = findViewById(R.id.wallet_add_70);
        amount80 = findViewById(R.id.wallet_add_80);
        amount90 = findViewById(R.id.wallet_add_90);
        amount100 = findViewById(R.id.wallet_add_100);
        amountChips = new TextView[]{amount10, amount20, amount30, amount40, amount50,
                amount60, amount70, amount80, amount90, amount100};
        customAmountInput = findViewById(R.id.wallet_custom_amount);
        emptyState = findViewById(R.id.wallet_history_empty);
        historyRecycler = findViewById(R.id.wallet_history_recycler);
    }

    /** Tapping a quick-amount chip selects it (drives the selected/unselected drawable state)
     *  and clears the custom field; typing a custom amount clears any chip selection. */
    private void setupAmountChips() {
        View.OnClickListener chipClick = v -> {
            TextView chip = (TextView) v;
            selectChip(chip);
            customAmountInput.setText("");
        };
        for (TextView chip : amountChips) {
            if (chip != null) chip.setOnClickListener(chipClick);
        }
        // ₹10 is selected by default (set here in code, not in XML, so aapt2 doesn't
        // need to resolve android:selected as a static layout attribute).
        selectChip(amount10);

        customAmountInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.length() > 0) selectChip(null);
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
    }

    private void selectChip(TextView chip) {
        for (TextView c : amountChips) {
            if (c != null) c.setSelected(c == chip);
        }
        selectedChip = chip;
    }

    private void setupHistoryList() {
        transactionAdapter = new TransactionAdapter(transactions);
        historyRecycler.setLayoutManager(new LinearLayoutManager(this));
        historyRecycler.setAdapter(transactionAdapter);
        historyRecycler.setNestedScrollingEnabled(false);
        renderHistoryVisibility();
    }

    private void renderHistoryVisibility() {
        boolean hasTransactions = !transactions.isEmpty();
        emptyState.setVisibility(hasTransactions ? View.GONE : View.VISIBLE);
        historyRecycler.setVisibility(hasTransactions ? View.VISIBLE : View.GONE);
    }

    private void startPayment(int amount) {
        if (amount < 1 || amount > 100000) {
            toast(getString(R.string.wallet_amount_range_error));
            return;
        }
        toast(getString(R.string.wallet_creating_order));
        String mobile = getSharedPreferences("profile", MODE_PRIVATE).getString("phone", "");
        ZapUpiClient.createOrder(amount, mobile, new ZapUpiClient.Callback() {
            @Override public void onSuccess(String paymentUrl, String orderId) {
                // Remember this order (survives the app being killed while the Custom
                // Tab is in front) so onResume() can verify + credit it immediately
                // the moment the user comes back, instead of waiting on the webhook.
                getSharedPreferences(WALLET_PREFS, MODE_PRIVATE).edit()
                        .putString(PREF_PENDING_ORDER_ID, orderId).apply();
                // No confirmation dialog — go straight to the payment page.
                openInCustomTab(paymentUrl);
            }
            @Override public void onError(String message) { toast(message); }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        verifyPendingOrderIfAny();
    }

    /** Runs whenever this screen comes back to the foreground (e.g. the user closed the
     *  Custom Tab after paying). Asks the backend to re-check the last order with ZapUPI
     *  right now and credit the wallet immediately if it succeeded, instead of leaving the
     *  user waiting on ZapUPI's webhook to arrive on its own schedule. */
    private void verifyPendingOrderIfAny() {
        android.content.SharedPreferences prefs = getSharedPreferences(WALLET_PREFS, MODE_PRIVATE);
        String orderId = prefs.getString(PREF_PENDING_ORDER_ID, null);
        if (orderId == null) return;

        ZapUpiClient.verifyOrder(orderId, new ZapUpiClient.VerifyCallback() {
            @Override public void onResult(String status) {
                if ("credited".equals(status) || "failed".equals(status)) {
                    prefs.edit().remove(PREF_PENDING_ORDER_ID).apply();
                    if ("credited".equals(status)) toast(getString(R.string.wallet_add_success));
                }
                // "pending" is left in prefs — the next onResume (or app reopen) retries it.
            }
            @Override public void onError(String message) {
                // Silent: webhook may still credit it independently, and the next
                // onResume will retry the check.
            }
        });
    }

    /** Binds Chrome's Custom Tabs service ahead of time so the actual browser
     *  launch later (in openInCustomTab) doesn't pay the cold-start cost. */
    private void warmUpCustomTabs() {
        try {
            String packageName = CustomTabsClient.getPackageName(this, null);
            if (packageName == null) return; // no compatible browser installed
            customTabsConnection = new CustomTabsServiceConnection() {
                @Override public void onCustomTabsServiceConnected(
                        android.content.ComponentName name, CustomTabsClient client) {
                    client.warmup(0L);
                    customTabsSession = client.newSession(null);
                    if (customTabsSession != null) customTabsSession.mayLaunchUrl(null, null, null);
                }

                @Override public void onServiceDisconnected(android.content.ComponentName name) {
                    customTabsSession = null;
                }
            };
            CustomTabsClient.bindCustomTabsService(this, packageName, customTabsConnection);
        } catch (Exception ignored) {
            // Best-effort only — openInCustomTab() falls back to a plain launch either way.
        }
    }

    private void openInCustomTab(String url) {
        try {
            if (customTabsSession != null) {
                customTabsSession.mayLaunchUrl(Uri.parse(url), null, null);
            }
            CustomTabColorSchemeParams colorParams = new CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(0xFFB00020)
                    .build();
            CustomTabsIntent.Builder builder = customTabsSession != null
                    ? new CustomTabsIntent.Builder(customTabsSession)
                    : new CustomTabsIntent.Builder();
            CustomTabsIntent customTabsIntent = builder
                    .setDefaultColorSchemeParams(colorParams)
                    .setShowTitle(true)
                    .build();
            customTabsIntent.launchUrl(this, Uri.parse(url));
        } catch (Exception e) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
            catch (Exception e2) { toast("Could not open payment page"); }
        }
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

    /** Reads transactions from wallet/transactions/{uid}; adjust the path to match your backend
     *  schema once payment/withdrawal history is being written server-side. Safe no-op (keeps the
     *  empty state) if the node doesn't exist yet. */
    private void loadTransactionHistory() {
        com.google.firebase.auth.FirebaseUser user = FirebaseRepository.currentUser();
        if (user == null) return;

        historyListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                transactions.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    Transaction t = child.getValue(Transaction.class);
                    if (t != null) transactions.add(t);
                }
                transactionAdapter.notifyDataSetChanged();
                renderHistoryVisibility();
            }

            @Override public void onCancelled(DatabaseError error) {
                // Keep showing the empty state; history is non-critical to the wallet flow.
            }
        };
        FirebaseRepository.users().child(user.getUid()).child("wallet").child("transactions")
                .addValueEventListener(historyListener);
    }

    @Override protected void onDestroy() {
        com.google.firebase.auth.FirebaseUser user = FirebaseRepository.currentUser();
        if (user != null) {
            if (balanceListener != null) {
                FirebaseRepository.users().child(user.getUid()).child("wallet").child("balance")
                        .removeEventListener(balanceListener);
            }
            if (historyListener != null) {
                FirebaseRepository.users().child(user.getUid()).child("wallet").child("transactions")
                        .removeEventListener(historyListener);
            }
        }
        if (customTabsConnection != null) {
            try { unbindService(customTabsConnection); } catch (Exception ignored) { }
            customTabsConnection = null;
            customTabsSession = null;
        }
        super.onDestroy();
    }

    private void render() {
        total.setText("₹" + balance);
        depositAmount.setText("₹0");
        winningAmount.setText("₹" + balance);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}

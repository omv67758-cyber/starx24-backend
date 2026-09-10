package com.arenax.tournament;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.arenax.tournament.data.FirebaseRepository;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;

/** A16/B41 — Help Center: searchable, collapsible FAQ read live from Firebase, with an
 *  escalation button into Support if the player still needs help. */
public class FaqActivity extends AppCompatActivity {
    private final List<DataSnapshot> allFaqs = new ArrayList<>();
    private LinearLayout list;
    private TextView empty;
    private ValueEventListener listener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScreen();
        loadFaqs();
    }

    private EditText search;

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(5, 6, 8));
        root.setPadding(dp(18), dp(18), dp(18), dp(18));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = label("‹", 34, Color.WHITE, true);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(42), dp(54)));
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.addView(label("HELP CENTER", 10, Color.rgb(237, 47, 62), true));
        heading.addView(label("FAQ", 21, Color.WHITE, true));
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        search = new EditText(this);
        search.setHint("Search help articles…");
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(Color.rgb(150, 150, 158));
        search.setBackgroundResource(R.drawable.bg_input);
        search.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, -2);
        searchParams.topMargin = dp(12);
        searchParams.bottomMargin = dp(10);
        search.setLayoutParams(searchParams);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { render(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
        root.addView(search);

        empty = label("No FAQs match your search yet. Tap below to contact support.", 14, Color.LTGRAY, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(40), 0, 0);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        NestedScrollView scroll = new NestedScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(list);
        container.addView(empty);
        scroll.addView(container);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        MaterialButton contact = new MaterialButton(this);
        contact.setText("STILL NEED HELP? CONTACT SUPPORT");
        contact.setTextColor(Color.WHITE);
        contact.setTextSize(12);
        contact.setAllCaps(false);
        contact.setCornerRadius(dp(14));
        contact.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(237, 47, 62)));
        LinearLayout.LayoutParams contactParams = new LinearLayout.LayoutParams(-1, dp(50));
        contactParams.topMargin = dp(10);
        contact.setLayoutParams(contactParams);
        contact.setOnClickListener(v -> startActivity(new android.content.Intent(this, SupportActivity.class)));
        root.addView(contact);
        setContentView(root);
    }

    private void loadFaqs() {
        if (!FirebaseRepository.isReady(this)) { toast("Live help data is unavailable right now."); return; }
        listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                allFaqs.clear();
                for (DataSnapshot child : snapshot.getChildren()) allFaqs.add(child);
                render(search == null ? "" : search.getText().toString());
            }
            @Override public void onCancelled(@NonNull DatabaseError error) { toast(error.getMessage()); }
        };
        FirebaseRepository.faq().addValueEventListener(listener);
    }

    private void render(String query) {
        list.removeAllViews();
        String needle = query == null ? "" : query.trim().toLowerCase();
        int shown = 0;
        for (DataSnapshot faq : allFaqs) {
            String question = String.valueOf(faq.child("question").getValue());
            String answer = String.valueOf(faq.child("answer").getValue());
            if (!needle.isEmpty() && !question.toLowerCase().contains(needle)
                    && !answer.toLowerCase().contains(needle)) continue;
            list.addView(buildRow(question, answer, faq.child("category").getValue()));
            shown++;
        }
        empty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }

    private View buildRow(String question, String answer, Object category) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.bg_card);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.bottomMargin = dp(10);
        row.setLayoutParams(rowParams);

        TextView q = label((category == null ? "" : "[" + category + "]  ") + question, 14, Color.WHITE, true);
        TextView a = label(answer, 13, Color.rgb(190, 190, 198), false);
        a.setPadding(0, dp(8), 0, 0);
        a.setVisibility(View.GONE);
        q.setOnClickListener(v -> a.setVisibility(a.getVisibility() == View.GONE ? View.VISIBLE : View.GONE));
        row.addView(q);
        row.addView(a);
        return row;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL);
        return v;
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override
    protected void onDestroy() {
        if (listener != null) FirebaseRepository.faq().removeEventListener(listener);
        super.onDestroy();
    }
}

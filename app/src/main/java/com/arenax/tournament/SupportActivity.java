package com.arenax.tournament;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.arenax.tournament.data.FirebaseRepository;
import com.arenax.tournament.util.InputValidation;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * A15/B42 — Customer Support / Help Center. User: create a ticket + see own tickets and replies.
 * Admin (adminMode=true): sees every ticket, can reply and close/reopen.
 */
public class SupportActivity extends AppCompatActivity {
    private static final String[] CATEGORIES = {
            "Account Problem", "Registration Problem", "Tournament Problem", "Match Problem",
            "Room ID/Password", "Result Problem", "Team Problem", "Technical Problem",
            "Report Player", "Other"
    };

    private boolean adminMode;
    private LinearLayout list;
    private TextView empty;
    private ValueEventListener listener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        adminMode = getIntent().getBooleanExtra("adminMode", false);
        buildScreen();
        loadTickets();
    }

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
        heading.addView(label(adminMode ? "ADMIN" : "HELP CENTER", 10, Color.rgb(237, 47, 62), true));
        heading.addView(label(adminMode ? "Support Tickets" : "My Tickets", 21, Color.WHITE, true));
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (!adminMode) {
            MaterialButton create = new MaterialButton(this);
            create.setText("+ NEW TICKET");
            create.setTextColor(Color.WHITE);
            create.setTextSize(11);
            create.setAllCaps(false);
            create.setCornerRadius(dp(14));
            create.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(237, 47, 62)));
            create.setOnClickListener(v -> showCreateTicketDialog());
            top.addView(create, new LinearLayout.LayoutParams(-2, dp(48)));
        }
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        empty = label(adminMode ? "No support tickets yet." : "You have no tickets. Tap + NEW TICKET if you need help.",
                15, Color.LTGRAY, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(60), 0, 0);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        NestedScrollView scroll = new NestedScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(list);
        container.addView(empty);
        scroll.addView(container);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void loadTickets() {
        if (!FirebaseRepository.isReady(this)) { toast("Live support data is unavailable right now."); return; }
        String uid = FirebaseAuth.getInstance().getUid();
        listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<DataSnapshot> tickets = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    String owner = child.child("userId").getValue(String.class);
                    if (adminMode || (owner != null && owner.equals(uid))) tickets.add(child);
                }
                list.removeAllViews();
                for (DataSnapshot ticket : tickets) list.addView(buildRow(ticket));
                empty.setVisibility(tickets.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                list.removeAllViews();
                empty.setText("Could not load tickets. Check your connection and try again.");
                empty.setVisibility(android.view.View.VISIBLE);
                toast(error.getMessage());
            }
        };
        FirebaseRepository.support().addValueEventListener(listener);
    }

    private android.view.View buildRow(DataSnapshot ticket) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.bg_card);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.bottomMargin = dp(10);
        row.setLayoutParams(rowParams);

        String subject = String.valueOf(ticket.child("subject").getValue());
        String category = String.valueOf(ticket.child("category").getValue());
        String description = String.valueOf(ticket.child("description").getValue());
        String status = String.valueOf(ticket.child("status").getValue());
        String reply = String.valueOf(ticket.child("reply").getValue());

        row.addView(label(subject, 15, Color.WHITE, true));
        row.addView(label(category + "  •  " + status, 11, Color.rgb(237, 47, 62), true));
        TextView desc = label(description, 13, Color.rgb(200, 200, 208), false);
        desc.setPadding(0, dp(6), 0, dp(6));
        row.addView(desc);
        if (reply != null && !reply.isEmpty() && !"null".equals(reply)) {
            TextView replyView = label("Support: " + reply, 13, Color.rgb(46, 204, 143), false);
            row.addView(replyView);
        }
        if (adminMode) {
            MaterialButton replyBtn = new MaterialButton(this);
            replyBtn.setText("REPLY / CLOSE");
            replyBtn.setTextColor(Color.WHITE);
            replyBtn.setTextSize(11);
            replyBtn.setAllCaps(false);
            replyBtn.setCornerRadius(dp(12));
            replyBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(60, 63, 74)));
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(-2, dp(40));
            btnParams.topMargin = dp(8);
            replyBtn.setLayoutParams(btnParams);
            replyBtn.setOnClickListener(v -> showReplyDialog(ticket.getKey(), reply));
            row.addView(replyBtn);
        }
        return row;
    }

    private void showReplyDialog(String ticketId, String existingReply) {
        EditText input = new EditText(this);
        input.setHint("Reply to player");
        if (existingReply != null && !"null".equals(existingReply)) input.setText(existingReply);
        input.setTextColor(Color.WHITE);
        input.setPadding(dp(16), dp(14), dp(16), dp(14));
        input.setBackgroundResource(R.drawable.bg_input);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(24), dp(8), dp(24), dp(8));
        wrap.addView(input);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Reply to ticket")
                .setView(wrap)
                .setNegativeButton("CANCEL", null)
                .setNeutralButton("MARK RESOLVED", (d, w) ->
                        submitReply(ticketId, input, "RESOLVED")
                                .addOnSuccessListener(u -> toast("Ticket resolved.")))
                .setPositiveButton("SEND REPLY", (d, w) ->
                        submitReply(ticketId, input, "IN_PROGRESS")
                                .addOnSuccessListener(u -> toast("Reply sent.")))
                .show();
    }

    private com.google.android.gms.tasks.Task<Void> submitReply(String ticketId, EditText input, String status) {
        if (!InputValidation.length(input, 2, 2000, "Reply must be 2–2000 characters")) {
            return com.google.android.gms.tasks.Tasks.forException(
                    new IllegalArgumentException("Reply must be 2–2000 characters"));
        }
        return FirebaseRepository.replyToTicket(ticketId, InputValidation.value(input), status);
    }

    private void showCreateTicketDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), dp(8));

        Spinner category = new Spinner(this);
        category.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, CATEGORIES));
        EditText subject = field("Subject");
        EditText description = field("Describe the problem");
        form.addView(category);
        form.addView(subject);
        form.addView(description);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Create support ticket")
                .setView(form)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SUBMIT", (d, w) -> {
                    String subjectValue = subject.getText().toString().trim();
                    if (!InputValidation.length(subject, 3, 120, "Subject must be 3–120 characters")
                            || !InputValidation.length(description, 10, 4000,
                            "Please describe the problem in 10–4000 characters")) {
                        toast("Please correct the highlighted fields.");
                        return;
                    }
                    FirebaseRepository.createTicket(String.valueOf(category.getSelectedItem()),
                                    InputValidation.value(subject), InputValidation.value(description))
                            .addOnSuccessListener(u -> toast("Ticket submitted. Support will reply soon."))
                            .addOnFailureListener(e -> toast(e.getMessage()));
                })
                .show();
    }

    private EditText field(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.rgb(150, 150, 158));
        input.setBackgroundResource(R.drawable.bg_input);
        input.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(6);
        params.bottomMargin = dp(6);
        input.setLayoutParams(params);
        return input;
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
        if (listener != null) FirebaseRepository.support().removeEventListener(listener);
        super.onDestroy();
    }
}

package com.arenax.tournament;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.arenax.tournament.data.FirebaseRepository;
import com.arenax.tournament.util.InputValidation;
import com.arenax.tournament.model.Team;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A9/B39 — Team Management / Team Area. Create a team, join by Team ID, view members, leave. */
public class TeamActivity extends AppCompatActivity {
    private LinearLayout list;
    private TextView empty;
    private ValueEventListener listener;
    private String myName = "Player";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScreen();
        loadMyName();
        loadTeams();
    }

    private void loadMyName() {
        String uid = FirebaseRepository.currentUser() == null ? null : FirebaseRepository.currentUser().getUid();
        if (uid == null) return;
        FirebaseRepository.users().child(uid).child("name").get()
                .addOnSuccessListener(s -> { if (s.getValue() != null) myName = String.valueOf(s.getValue()); });
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
        heading.addView(label("TEAM AREA", 10, Color.rgb(237, 47, 62), true));
        heading.addView(label("My Teams", 21, Color.WHITE, true));
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = dp(10);
        actionsParams.bottomMargin = dp(14);
        actions.setLayoutParams(actionsParams);

        MaterialButton create = new MaterialButton(this);
        create.setText("+ CREATE TEAM");
        create.setTextColor(Color.WHITE);
        create.setTextSize(11);
        create.setAllCaps(false);
        create.setCornerRadius(dp(14));
        create.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(237, 47, 62)));
        create.setOnClickListener(v -> showCreateDialog());
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, dp(48), 1);
        p1.rightMargin = dp(6);
        actions.addView(create, p1);

        MaterialButton join = new MaterialButton(this);
        join.setText("JOIN BY TEAM ID");
        join.setTextColor(Color.rgb(237, 47, 62));
        join.setTextSize(11);
        join.setAllCaps(false);
        join.setCornerRadius(dp(14));
        join.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(38, 38, 47)));
        join.setOnClickListener(v -> showJoinDialog());
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, dp(48), 1);
        p2.leftMargin = dp(6);
        actions.addView(join, p2);
        root.addView(actions);

        empty = label("You are not part of any team yet. Create one or join with a Team ID.",
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

    private void loadTeams() {
        if (!FirebaseRepository.isReady(this)) { toast("Live team data is unavailable right now."); return; }
        String uid = FirebaseAuth.getInstance().getUid();
        listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<Team> myTeams = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    @SuppressWarnings("unchecked")
                    Team team = Team.fromMap(child.getKey(), (Map<String, Object>) child.getValue());
                    if (team != null && uid != null && team.getMembers().containsKey(uid)) myTeams.add(team);
                }
                list.removeAllViews();
                for (Team team : myTeams) list.addView(buildRow(team, uid));
                empty.setVisibility(myTeams.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) { toast(error.getMessage()); }
        };
        FirebaseRepository.teams().addValueEventListener(listener);
    }

    private android.view.View buildRow(Team team, String uid) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.bg_card);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.bottomMargin = dp(10);
        row.setLayoutParams(rowParams);

        boolean isCaptain = uid != null && uid.equals(team.getCaptainId());
        row.addView(label(team.getName(), 16, Color.WHITE, true));
        row.addView(label((isCaptain ? "CAPTAIN" : "MEMBER") + "  •  " + team.getStatus()
                + "  •  " + team.getMemberCount() + " players", 11, Color.rgb(237, 47, 62), true));
        TextView idRow = label("TEAM ID: " + team.getId() + "  (tap to copy & invite)", 12, Color.rgb(200, 200, 208), false);
        idRow.setPadding(0, dp(6), 0, dp(6));
        idRow.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Team ID", team.getId()));
            toast("Copied — share this Team ID to invite players.");
        });
        row.addView(idRow);

        StringBuilder members = new StringBuilder();
        for (String name : team.getMembers().values()) {
            if (members.length() > 0) members.append(", ");
            members.append(name == null || name.isEmpty() ? "Player" : name);
        }
        row.addView(label(members.toString(), 12, Color.rgb(160, 160, 170), false));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnRowParams = new LinearLayout.LayoutParams(-1, -2);
        btnRowParams.topMargin = dp(10);
        btnRow.setLayoutParams(btnRowParams);
        if (!isCaptain) {
            btnRow.addView(smallButton("LEAVE TEAM", Color.rgb(60, 63, 74), v ->
                    FirebaseRepository.leaveTeam(team.getId())
                            .addOnSuccessListener(u -> toast("You left " + team.getName() + "."))
                            .addOnFailureListener(e -> toast(e.getMessage()))));
        } else {
            btnRow.addView(smallButton("TRANSFER LEADERSHIP", Color.rgb(60, 63, 74), v -> showTransferDialog(team)));
        }
        row.addView(btnRow);
        return row;
    }

    private MaterialButton smallButton(String text, int bg, android.view.View.OnClickListener listener) {
        MaterialButton b = new MaterialButton(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setCornerRadius(dp(12));
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(bg));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(40));
        b.setLayoutParams(p);
        b.setOnClickListener(listener);
        return b;
    }

    private void showTransferDialog(Team team) {
        EditText input = field("New captain's UID");
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(24), dp(8), dp(24), dp(8));
        wrap.addView(input);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Transfer team leadership")
                .setMessage("Leadership can only be transferred to an existing team member's user ID.")
                .setView(wrap)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("TRANSFER", (d, w) -> {
                    String newCaptain = InputValidation.value(input);
                    if (!InputValidation.safeId(input, "Enter a valid user ID")
                            || !team.getMembers().containsKey(newCaptain)) {
                        toast("Enter the UID of an existing member.");
                        return;
                    }
                    FirebaseRepository.teams().child(team.getId()).child("captainId").setValue(newCaptain)
                            .addOnSuccessListener(u -> toast("Leadership transferred."))
                            .addOnFailureListener(e -> toast(e.getMessage()));
                })
                .show();
    }

    private void showCreateDialog() {
        EditText name = field("Team name");
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(24), dp(8), dp(24), dp(8));
        wrap.addView(name);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Create a team")
                .setView(wrap)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("CREATE", (d, w) -> {
                    if (!InputValidation.length(name, 3, 40, "Team name must be 3–40 characters")) {
                        toast("Enter a valid team name.");
                        return;
                    }
                    FirebaseRepository.createTeam(InputValidation.value(name), myName)
                            .addOnSuccessListener(u -> toast("Team created."))
                            .addOnFailureListener(e -> toast(e.getMessage()));
                })
                .show();
    }

    private void showJoinDialog() {
        EditText id = field("Team ID shared by your captain");
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(24), dp(8), dp(24), dp(8));
        wrap.addView(id);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Join a team")
                .setView(wrap)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("JOIN", (d, w) -> {
                    if (!InputValidation.safeId(id, "Enter a valid Team ID")) {
                        toast("Enter a valid Team ID.");
                        return;
                    }
                    FirebaseRepository.joinTeam(InputValidation.value(id), myName)
                            .addOnSuccessListener(u -> toast("Joined team."))
                            .addOnFailureListener(e -> toast("Could not join — check the Team ID."));
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

    private void toast(String message) { Toast.makeText(this, message == null ? "Action failed" : message, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override
    protected void onDestroy() {
        if (listener != null) FirebaseRepository.teams().removeEventListener(listener);
        super.onDestroy();
    }
}

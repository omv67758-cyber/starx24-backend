package com.arenax.tournament;

import android.os.Bundle;
import android.os.CountDownTimer;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.NonNull;

import com.arenax.tournament.model.Tournament;
import com.arenax.tournament.data.FirebaseRepository;
import com.arenax.tournament.util.InputValidation;
import com.bumptech.glide.Glide;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

public class TournamentDetailActivity extends AppCompatActivity {
    private Tournament tournament;
    private CountDownTimer countdownTimer;
    private TextView countdown;
    private TextView roomState;
    private MaterialButton join;
    private ValueEventListener participantsListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tournament_detail);
        if (!FirebaseRepository.isReady(this)) {
            Toast.makeText(this, "Live services are unavailable. Please try again later.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        tournament = getIntentTournament();
        if (tournament == null) {
            finish();
            return;
        }

        ((TextView) findViewById(R.id.detail_title)).setText(tournament.getTitle());
        ((TextView) findViewById(R.id.detail_mode)).setText(tournament.getMode());
        ((TextView) findViewById(R.id.detail_date)).setText("📅  " + tournament.getDate() + "    🕒  " + tournament.getTime());
        ((TextView) findViewById(R.id.detail_prize)).setText("🪙 " + tournament.getPrizePoolCoins());
        ((TextView) findViewById(R.id.detail_format)).setText("🪙 " + tournament.getEntryFeeCoins());
        ((TextView) findViewById(R.id.detail_description)).setText(tournament.getDescription());
        ((TextView) findViewById(R.id.detail_map)).setText(tournament.getMap());
        ((TextView) findViewById(R.id.detail_rules)).setText(
                tournament.getRules().isEmpty() ? "Play fair. Follow the room rules shared by the admin." : tournament.getRules());
        updateSlotsUi(tournament.getJoinedSlots(), tournament.getTotalSlots());
        countdown = findViewById(R.id.detail_countdown);
        roomState = findViewById(R.id.detail_room_state);
        roomState.setOnClickListener(v -> {
            if (tournament.isRoomReleased() && !tournament.getRoomId().isEmpty()) {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Room",
                        "Room ID: " + tournament.getRoomId() + " | Password: " + tournament.getRoomPassword()));
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
            }
        });
        if (!tournament.getBannerUrl().isEmpty()) {
            Glide.with(this).load(tournament.getBannerUrl()).centerCrop()
                    .into((android.widget.ImageView) findViewById(R.id.detail_banner));
        }
        startCountdown();
        updateRoomState(false);
        watchParticipants();
        watchLiveRoomUpdates();

        findViewById(R.id.button_back).setOnClickListener(v -> finish());
        findViewById(R.id.button_report).setOnClickListener(v -> showReportOrDisputeChoice());
        findViewById(R.id.button_matches).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, MyMatchesActivity.class)
                        .putExtra(MyMatchesActivity.EXTRA_TOURNAMENT_ID, tournament.getId())));
        join = findViewById(R.id.button_join);
        join.setOnClickListener(v -> {
            if (FirebaseAuth.getInstance().getCurrentUser() == null) {
                Toast.makeText(this, "Please sign in before joining.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!tournament.isActive() || "COMPLETED".equalsIgnoreCase(tournament.getStatus())
                    || !"OPEN".equalsIgnoreCase(tournament.getRegistrationStatus())) {
                Toast.makeText(this, "Registration is closed for this match.", Toast.LENGTH_SHORT).show();
                return;
            }
            String uid = FirebaseRepository.currentUser() == null ? null : FirebaseRepository.currentUser().getUid();
            FirebaseRepository.tournamentParticipants(tournament.getId()).child(uid).get()
                    .addOnSuccessListener(snapshot -> {
                        if (snapshot.exists()) {
                            join.setText("ALREADY REGISTERED");
                            join.setEnabled(false);
                            Toast.makeText(this, "You are already registered.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        // A8 / B32 — once every slot is filled, route the player onto the free
                        // waitlist instead of blocking them outright.
                        if (tournament.getRemainingSlots() <= 0) {
                            joinWaitlistFlow(uid);
                            return;
                        }
                        join.setEnabled(false);
                        FirebaseRepository.registerForTournament(
                                        tournament.getId(), "", tournament.getTeamSize())
                                .addOnSuccessListener(ignored -> {
                                    join.setText("REGISTERED");
                                    Toast.makeText(this, "Match registration confirmed.", Toast.LENGTH_LONG).show();
                                    updateRoomState(true);
                                })
                                .addOnFailureListener(error -> {
                                    join.setEnabled(true);
                                    Toast.makeText(this, "Could not register right now.", Toast.LENGTH_LONG).show();
                                });
                    })
                    .addOnFailureListener(error ->
                            Toast.makeText(this, "Could not verify your registration.", Toast.LENGTH_LONG).show());
        });
    }

    /** A8 / B32 — Free waitlist join for a full match. Checks for a duplicate entry first so
     *  tapping "JOIN WAITLIST" twice never creates two waitlist records. */
    private void joinWaitlistFlow(String uid) {
        FirebaseRepository.tournamentWaitlist(tournament.getId()).child(uid).get()
                .addOnSuccessListener(waitSnapshot -> {
                    if (waitSnapshot.exists()) {
                        join.setText("WAITLISTED");
                        join.setEnabled(false);
                        Toast.makeText(this, "You're already on the waitlist. We'll notify you if a slot opens.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    join.setEnabled(false);
                    FirebaseRepository.joinWaitlist(tournament.getId())
                            .addOnSuccessListener(ignored -> {
                                join.setText("WAITLISTED");
                                Toast.makeText(this,
                                        "Match is full — you're on the free waitlist and will be notified if a slot opens.",
                                        Toast.LENGTH_LONG).show();
                            })
                            .addOnFailureListener(error -> {
                                join.setEnabled(true);
                                Toast.makeText(this, "Could not join the waitlist right now.", Toast.LENGTH_LONG).show();
                            });
                })
                .addOnFailureListener(error ->
                        Toast.makeText(this, "Could not check the waitlist.", Toast.LENGTH_LONG).show());
    }

    @SuppressWarnings("deprecation")
    private Tournament getIntentTournament() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            return getIntent().getSerializableExtra("tournament", Tournament.class);
        }
        return (Tournament) getIntent().getSerializableExtra("tournament");
    }

    private void startCountdown() {
        if (tournament.isDelayed()) {
            countdown.setText("MATCH DELAYED  •  WAIT FOR ROOM ID & PASSWORD");
            countdown.setTextColor(0xFFFFB020);
            return;
        }
        long target = tournament.getEffectiveStartAt();
        if (target <= System.currentTimeMillis()) {
            countdown.setText("WAIT FOR ROOM ID & PASSWORD");
            return;
        }
        countdownTimer = new CountDownTimer(target - System.currentTimeMillis(), 1000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                long seconds = millisUntilFinished / 1000L;
                long hours = seconds / 3600L;
                long minutes = (seconds % 3600L) / 60L;
                long remaining = seconds % 60L;
                // Escalating urgency: bright red under 15 min, neon pulse under 5 min.
                if (millisUntilFinished < 5 * 60 * 1000L) {
                    countdown.setTextColor(0xFFFF3B3B);
                } else if (millisUntilFinished < 15 * 60 * 1000L) {
                    countdown.setTextColor(0xFFE01414);
                }
                countdown.setText(String.format(java.util.Locale.ROOT,
                        "STARTS IN  %02dh %02dm %02ds", hours, minutes, remaining));
            }

            @Override
            public void onFinish() {
                countdown.setText("WAIT FOR ROOM ID & PASSWORD");
            }
        }.start();
    }

    private void updateRoomState(boolean joined) {
        if (tournament.isDelayed()) {
            roomState.setText("MATCH DELAYED — " + tournament.getDelayReason() + "\nWAIT FOR ROOM ID & PASSWORD");
            return;
        }
        // Room only shows once the admin explicitly releases it — timestamp alone no longer reveals it.
        boolean released = tournament.isRoomReleased();
        if (!joined || !released) {
            roomState.setText(!joined
                    ? "ROOM DETAILS LOCKED  •  REGISTER TO VIEW"
                    : "WAIT FOR ROOM ID & PASSWORD");
            return;
        }
        String room = tournament.getRoomId().isEmpty() ? "Room ID will be posted by admin shortly." :
                "ROOM ID  " + tournament.getRoomId() + "\nPASSWORD  " + tournament.getRoomPassword()
                        + "\n\nTap to copy either value.";
        roomState.setText(room);
    }

    private void watchParticipants() {
        String uid = FirebaseRepository.currentUser() == null ? null : FirebaseRepository.currentUser().getUid();
        if (uid == null || tournament.getId() == null) return;
        participantsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                int registered = 0;
                boolean joined = false;
                for (DataSnapshot child : snapshot.getChildren()) {
                    registered++;
                    if (uid.equals(child.child("userId").getValue(String.class))) joined = true;
                }
                TextView slots = findViewById(R.id.detail_slots);
                int total = tournament.getTotalSlots();
                updateSlotsUi(registered, total);
                if (joined) {
                    join.setText("ALREADY REGISTERED");
                    join.setEnabled(false);
                } else if (registered >= total && total > 0) {
                    // A8 / B32 — full match still accepts free waitlist joins.
                    watchWaitlistState(uid);
                }
                lastJoined = joined;
                updateRoomState(joined);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        };
        FirebaseRepository.tournamentParticipants(tournament.getId())
                .addValueEventListener(participantsListener);
    }

    /** Reflects whether the signed-in user is already waitlisted for a full match. */
    private void watchWaitlistState(String uid) {
        if (uid == null || tournament.getId() == null) return;
        FirebaseRepository.tournamentWaitlist(tournament.getId()).child(uid).get()
                .addOnSuccessListener(snapshot -> {
                    if (snapshot.exists()) {
                        join.setText("WAITLISTED");
                        join.setEnabled(false);
                    } else {
                        join.setText("JOIN WAITLIST");
                        join.setEnabled(true);
                    }
                })
                .addOnFailureListener(error -> {
                    join.setText("MATCH FULL");
                    join.setEnabled(false);
                });
    }

    private ValueEventListener tournamentListener;
    private boolean lastJoined;

    /** Keeps room-release / delay state live so a Room Release from the admin appears instantly, no refresh needed. */
    private void watchLiveRoomUpdates() {
        if (tournament.getId() == null) return;
        tournamentListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                @SuppressWarnings("unchecked")
                Tournament fresh = Tournament.fromMap(tournament.getId(),
                        (java.util.Map<String, Object>) snapshot.getValue());
                if (fresh == null) return;
                tournament = fresh;
                updateRoomState(lastJoined);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        };
        FirebaseRepository.tournaments().child(tournament.getId()).addValueEventListener(tournamentListener);
    }

    /** A13/A14 & B43 — entry point for both Result Dispute and Report/Moderation flows. */
    private void showReportOrDisputeChoice() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Toast.makeText(this, "Please sign in first.", Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("What would you like to do?")
                .setMessage("Raise a dispute if you disagree with the published result, or report a rule violation / problem.")
                .setNegativeButton("CANCEL", null)
                .setNeutralButton("REPORT A PROBLEM", (d, w) -> showReportForm())
                .setPositiveButton("RAISE RESULT DISPUTE", (d, w) -> showDisputeForm())
                .show();
    }

    private void showDisputeForm() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        form.setPadding(pad, dp(8), pad, dp(8));
        Spinner reason = new Spinner(this);
        reason.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Wrong Result", "Missing Kills", "Wrong Placement", "Cheating Suspected", "Other"}));
        EditText description = field("Describe what happened (attach a screenshot in your ticket if needed)");
        form.addView(reason);
        form.addView(description);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Raise a dispute • " + tournament.getTitle())
                .setView(form)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SUBMIT DISPUTE", (d, w) -> {
                    if (!InputValidation.length(description, 10, 4000,
                            "Please describe the dispute in 10–4000 characters")) {
                        Toast.makeText(this, "Please correct the highlighted field.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    FirebaseRepository.submitDispute(tournament.getId(), tournament.getTitle(),
                                    String.valueOf(reason.getSelectedItem()), InputValidation.value(description))
                            .addOnSuccessListener(u -> Toast.makeText(this,
                                    "Dispute submitted. Admin will review it.", Toast.LENGTH_LONG).show())
                            .addOnFailureListener(e -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
                })
                .show();
    }

    private void showReportForm() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        form.setPadding(pad, dp(8), pad, dp(8));
        Spinner reason = new Spinner(this);
        reason.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Rule Violation", "Abusive Behavior", "Wrong Result", "Cheating", "Other"}));
        EditText description = field("Describe the problem");
        form.addView(reason);
        form.addView(description);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Report a problem • " + tournament.getTitle())
                .setView(form)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SUBMIT REPORT", (d, w) -> {
                    if (!InputValidation.length(description, 10, 4000,
                            "Please describe the problem in 10–4000 characters")) {
                        Toast.makeText(this, "Please correct the highlighted field.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    FirebaseRepository.submitReport("TOURNAMENT", tournament.getId(), tournament.getTitle(),
                                    String.valueOf(reason.getSelectedItem()), InputValidation.value(description))
                            .addOnSuccessListener(u -> Toast.makeText(this,
                                    "Report submitted. Moderators will review it.", Toast.LENGTH_LONG).show())
                            .addOnFailureListener(e -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
                })
                .show();
    }

    private EditText field(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setBackgroundResource(R.drawable.bg_input);
        int hPad = dp(16), vPad = dp(14);
        input.setPadding(hPad, vPad, hPad, vPad);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(6);
        params.bottomMargin = dp(6);
        input.setLayoutParams(params);
        return input;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    /** Updates the boxed slots-progress card: fill bar, "X/Y filled" text and "spots left". */
    private void updateSlotsUi(int joined, int total) {
        TextView slotsText = findViewById(R.id.detail_slots);
        TextView spotsLeft = findViewById(R.id.detail_spots_left);
        android.widget.ProgressBar bar = findViewById(R.id.detail_slots_progress);
        int remaining = Math.max(0, total - joined);
        slotsText.setText(joined + "/" + total + " FILLED");
        if (remaining <= 0) {
            spotsLeft.setText("FULL");
            spotsLeft.setTextColor(0xFFFF4B4B);
        } else {
            spotsLeft.setText(remaining + " SPOTS LEFT");
            spotsLeft.setTextColor(getColor(R.color.success));
        }
        int percent = total > 0 ? Math.min(100, Math.round(100f * joined / total)) : 0;
        bar.setProgress(percent);
    }

    @Override
    protected void onDestroy() {
        if (countdownTimer != null) countdownTimer.cancel();
        if (participantsListener != null && tournament != null && tournament.getId() != null) {
            FirebaseRepository.tournamentParticipants(tournament.getId())
                    .removeEventListener(participantsListener);
        }
        if (tournamentListener != null && tournament != null && tournament.getId() != null) {
            FirebaseRepository.tournaments().child(tournament.getId()).removeEventListener(tournamentListener);
        }
        super.onDestroy();
    }
}

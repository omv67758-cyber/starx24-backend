package com.arenax.tournament;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.arenax.tournament.data.FirebaseRepository;
import com.arenax.tournament.model.Match;
import com.arenax.tournament.model.Tournament;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * B34 (My Matches) + B35 (Match Live Page) combined into one live list:
 *   UPCOMING + countdown  →  WAIT FOR ROOM ID & PASSWORD  →  Room ID/Password + Copy  →  LIVE  →  COMPLETED
 * Launched with EXTRA_TOURNAMENT_ID from a tournament's detail screen (matches for that tournament only),
 * or with no extra from Profile → "My Matches" (aggregates matches across every tournament the player joined).
 */
public class MyMatchesActivity extends AppCompatActivity {
    public static final String EXTRA_TOURNAMENT_ID = "tournamentId";

    private LinearLayout content;
    private final List<CountDownTimer> timers = new ArrayList<>();
    private final List<ValueEventListener> liveListeners = new ArrayList<>();
    private final List<com.google.firebase.database.Query> liveQueries = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!FirebaseRepository.isReady(this)) {
            Toast.makeText(this, "Live services are unavailable. Please try again later.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.ink));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(18), dp(16), dp(14));
        TextView back = text("‹", 22, R.color.text_primary, true);
        back.setPadding(0, 0, dp(14), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        String tournamentId = getIntent().getStringExtra(EXTRA_TOURNAMENT_ID);
        TextView title = text(tournamentId != null ? "MATCHES" : "MY MATCHES", 18, R.color.text_primary, true);
        bar.addView(title);
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(4), dp(16), dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        if (tournamentId != null) {
            loadMatchesForTournament(tournamentId);
        } else {
            loadMyMatchesAcrossTournaments();
        }
    }

    private void loadMatchesForTournament(String tournamentId) {
        content.addView(pageText("Live schedule for this tournament. Countdown is computed from the admin's exact time and stays correct on refresh."));
        com.google.firebase.database.Query q = FirebaseRepository.matches().orderByChild("tournamentId").equalTo(tournamentId);
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) { renderMatches(snapshot); }
            @Override public void onCancelled(@NonNull DatabaseError error) { content.addView(pageText("Matches unavailable right now.")); }
        };
        q.addValueEventListener(listener);
        liveListeners.add(listener);
        liveQueries.add(q);
    }

    private void loadMyMatchesAcrossTournaments() {
        content.addView(pageText("Every match across the tournaments you've joined."));
        String uid = FirebaseRepository.currentUser() == null ? null : FirebaseRepository.currentUser().getUid();
        if (uid == null) {
            content.addView(pageText("Sign in to see your matches."));
            return;
        }
        FirebaseRepository.tournaments().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<String> myTournamentIds = new ArrayList<>();
                List<DataSnapshot> pending = new ArrayList<>();
                for (DataSnapshot t : snapshot.getChildren()) pending.add(t);
                checkNext(pending, 0, myTournamentIds, uid);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) { content.addView(pageText("Could not load your tournaments.")); }
        });
    }

    private void checkNext(List<DataSnapshot> all, int index, List<String> myTournamentIds, String uid) {
        if (index >= all.size()) {
            if (myTournamentIds.isEmpty()) {
                content.addView(pageText("You haven't registered for any tournament yet. Join one to see matches here."));
                return;
            }
            for (String tid : myTournamentIds) loadMatchesForTournament(tid);
            return;
        }
        DataSnapshot t = all.get(index);
        FirebaseRepository.tournamentParticipants(t.getKey()).child(uid).get()
                .addOnSuccessListener(p -> {
                    if (p.exists()) myTournamentIds.add(t.getKey());
                    checkNext(all, index + 1, myTournamentIds, uid);
                })
                .addOnFailureListener(e -> checkNext(all, index + 1, myTournamentIds, uid));
    }

    private void renderMatches(DataSnapshot snapshot) {
        if (!snapshot.exists() || snapshot.getChildrenCount() == 0) {
            content.addView(pageText("No matches scheduled yet."));
            return;
        }
        for (DataSnapshot child : snapshot.getChildren()) {
            @SuppressWarnings("unchecked")
            Match m = Match.fromMap(child.getKey(), (java.util.Map<String, Object>) child.getValue());
            if (m != null) content.addView(matchCard(m));
        }
    }

    private LinearLayout matchCard(Match m) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.surface));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), getColor(R.color.stroke));
        card.setBackground(bg);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(12);
        card.setLayoutParams(cp);

        TextView name = text((m.getTournamentTitle().isEmpty() ? "" : m.getTournamentTitle() + " — ")
                + "ROUND " + m.getRound() + " • " + m.getName(), 14, R.color.text_primary, true);
        card.addView(name);
        card.addView(pageText(m.getMap() + "  •  " + m.getMode()
                + (m.getScheduledAt() > 0 ? "  •  " + new SimpleDateFormat("dd MMM, hh:mm a", Locale.ENGLISH).format(new Date(m.getScheduledAt())) : "")));

        TextView state = text("", 13, R.color.text_primary, true);
        state.setPadding(0, dp(8), 0, 0);
        card.addView(state);

        String status = m.getEffectiveStatus();
        if (Match.CANCELLED.equals(status)) {
            state.setText("MATCH CANCELLED");
            state.setTextColor(getColor(R.color.text_muted));
        } else if (Match.COMPLETED.equals(status)) {
            state.setText("COMPLETED — check Results & Leaderboard");
            state.setTextColor(getColor(R.color.text_secondary));
        } else if (Match.LIVE.equals(status)) {
            state.setText("● LIVE NOW");
            state.setTextColor(getColor(R.color.blood_red));
            if (m.isRoomReleased()) card.addView(roomBlock(m));
        } else if (Match.DELAYED.equals(status) || m.isDelayed()) {
            state.setText("MATCH DELAYED — WAIT FOR ROOM ID & PASSWORD"
                    + (m.getDelayReason().isEmpty() ? "" : "\n" + m.getDelayReason()));
            state.setTextColor(getColor(R.color.warning));
        } else if (m.isRoomReleased()) {
            state.setText("ROOM RELEASED");
            state.setTextColor(getColor(R.color.success));
            card.addView(roomBlock(m));
        } else if (Match.WAITING.equals(status)) {
            state.setText("WAIT FOR ROOM ID & PASSWORD");
            state.setTextColor(getColor(R.color.warning));
        } else if (m.getScheduledAt() > 0) {
            startCountdown(state, m.getScheduledAt());
        } else {
            state.setText("SCHEDULE TBA");
            state.setTextColor(getColor(R.color.text_secondary));
        }
        return card;
    }

    private LinearLayout roomBlock(Match m) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.accent_green_tint));
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), getColor(R.color.accent_green_stroke));
        block.setBackground(bg);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(10);
        block.setLayoutParams(bp);

        TextView roomId = text("ROOM ID   " + (m.getRoomId().isEmpty() ? "Posting shortly…" : m.getRoomId()) + "   (tap to copy)", 13, R.color.text_primary, true);
        TextView roomPass = text("PASSWORD   " + (m.getRoomPassword().isEmpty() ? "—" : m.getRoomPassword()) + "   (tap to copy)", 13, R.color.text_primary, true);
        roomId.setPadding(0, dp(4), 0, dp(4));
        roomPass.setPadding(0, dp(4), 0, dp(4));
        roomId.setOnClickListener(v -> copy("Room ID", m.getRoomId()));
        roomPass.setOnClickListener(v -> copy("Password", m.getRoomPassword()));
        block.addView(roomId);
        block.addView(roomPass);
        return block;
    }

    private void copy(String label, String value) {
        if (value == null || value.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    /** Countdown computed from the stored scheduled timestamp (not a client interval start), so it stays
     *  correct after refresh/app-restart — matches A6's real-time countdown requirement. */
    private void startCountdown(TextView target, long scheduledAt) {
        long remaining = scheduledAt - System.currentTimeMillis();
        if (remaining <= 0) {
            target.setText("WAIT FOR ROOM ID & PASSWORD");
            target.setTextColor(getColor(R.color.warning));
            return;
        }
        CountDownTimer timer = new CountDownTimer(remaining, 1000L) {
            @Override public void onTick(long millisUntilFinished) {
                long secs = millisUntilFinished / 1000L;
                long days = secs / 86400L;
                long hours = (secs % 86400L) / 3600L;
                long minutes = (secs % 3600L) / 60L;
                long seconds = secs % 60L;
                if (millisUntilFinished < 5 * 60 * 1000L) {
                    target.setTextColor(0xFFFF3B3B);
                } else if (millisUntilFinished < 15 * 60 * 1000L) {
                    target.setTextColor(0xFFE01414);
                } else {
                    target.setTextColor(getColor(R.color.text_primary));
                }
                target.setText(String.format(Locale.ROOT, "%02dd %02dh %02dm %02ds", days, hours, minutes, seconds));
            }
            @Override public void onFinish() {
                target.setText("WAIT FOR ROOM ID & PASSWORD");
                target.setTextColor(getColor(R.color.warning));
            }
        };
        timer.start();
        timers.add(timer);
    }

    private TextView text(String value, int sizeSp, int colorRes, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sizeSp);
        t.setTextColor(getColor(colorRes));
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private TextView pageText(String value) {
        TextView t = text(value, 12, R.color.text_secondary, false);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override
    protected void onDestroy() {
        for (CountDownTimer timer : timers) timer.cancel();
        for (int i = 0; i < liveQueries.size(); i++) liveQueries.get(i).removeEventListener(liveListeners.get(i));
        super.onDestroy();
    }
}

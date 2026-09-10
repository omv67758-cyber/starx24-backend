package com.arenax.tournament;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.arenax.tournament.data.FirebaseRepository;
import com.arenax.tournament.model.Match;
import com.arenax.tournament.model.Team;
import com.arenax.tournament.model.Tournament;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TournamentControlCenterActivity extends AppCompatActivity {
    private static final String[] TABS = {
            "Overview", "Participants", "Teams", "Matches", "Schedule", "Leaderboard", "Results",
            "Rules", "Announcements", "Reports", "Support Issues", "Activity", "Settings"
    };

    private String tournamentId;
    private String title;
    private Tournament tournament;
    private final Map<String, ValueEventListener> listeners = new HashMap<>();
    private LinearLayout content;
    private TextView status;
    private int selectedTab;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tournamentId = getIntent().getStringExtra("tournamentId");
        title = getIntent().getStringExtra("title");
        if (tournamentId == null) tournamentId = "";
        if (title == null || title.trim().isEmpty()) title = "Tournament Control Center";
        buildScreen();
        loadTournament();
    }

    private void buildScreen() {
        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(Color.rgb(5, 6, 8));

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.addView(container, new ScrollView.LayoutParams(-1, -2));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = label("‹", 34, Color.WHITE, true);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(42), dp(54)));
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.addView(label("CONTROL CENTER", 10, Color.rgb(237, 47, 62), true));
        heading.addView(label(title, 21, Color.WHITE, true));
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        container.addView(top);

        status = label("Loading tournament…", 13, Color.LTGRAY, false);
        status.setPadding(0, dp(8), 0, dp(10));
        container.addView(status);

        HorizontalScrollView tabs = new HorizontalScrollView(this);
        tabs.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < TABS.length; i++) {
            final int index = i;
            MaterialButton tab = new MaterialButton(this);
            tab.setText(TABS[i]);
            tab.setAllCaps(false);
            tab.setTextColor(Color.WHITE);
            tab.setTextSize(11);
            tab.setCornerRadius(dp(18));
            tab.setInsetTop(0);
            tab.setInsetBottom(0);
            tab.setElevation(0f);
            tab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(28, 28, 35)));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(40));
            p.rightMargin = dp(8);
            tab.setLayoutParams(p);
            tab.setOnClickListener(v -> switchTab(index));
            tabRow.addView(tab);
        }
        tabs.addView(tabRow);
        container.addView(tabs);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(14), 0, dp(18));
        container.addView(content);
        setContentView(root);
        switchTab(0);
    }

    private void loadTournament() {
        if (!FirebaseRepository.isReady(this)) {
            status.setText("Firebase unavailable. Control center is read-only until the backend is ready.");
            showMessage("Firebase unavailable right now.");
            return;
        }
        FirebaseRepository.tournaments().child(tournamentId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                tournament = Tournament.fromMap(snapshot.getKey(), castMap(snapshot.getValue()));
                if (tournament == null) {
                    status.setText("Tournament record not found.");
                    renderCurrentTab();
                    return;
                }
                status.setText("Connected to live Firebase data.");
                renderCurrentTab();
            }
            @Override public void onCancelled(DatabaseError error) {
                status.setText("Tournament load cancelled: " + error.getMessage());
                showMessage("Could not load tournament: " + error.getMessage());
            }
        });
    }

    private void switchTab(int index) {
        selectedTab = index;
        renderCurrentTab();
    }

    private void renderCurrentTab() {
        removeListeners();
        content.removeAllViews();
        if (tournament == null) {
            content.addView(stateView("Loading", "Waiting for live tournament data from Firebase."));
            return;
        }
        String tab = TABS[selectedTab];
        if ("Overview".equals(tab)) renderOverview();
        else if ("Participants".equals(tab)) renderParticipants();
        else if ("Teams".equals(tab)) renderTeams();
        else if ("Matches".equals(tab) || "Schedule".equals(tab)) renderMatches("Schedule".equals(tab));
        else if ("Leaderboard".equals(tab)) renderLeaderboard();
        else if ("Results".equals(tab)) renderResults();
        else if ("Rules".equals(tab)) renderRules();
        else if ("Announcements".equals(tab)) renderAnnouncements();
        else if ("Reports".equals(tab)) renderReports();
        else if ("Support Issues".equals(tab)) renderSupportIssues();
        else if ("Activity".equals(tab)) renderActivity();
        else if ("Settings".equals(tab)) renderSettings();
    }

    private void renderOverview() {
        content.addView(card(title, tournament.getDescription()));
        int total = Math.max(0, tournament.getTotalSlots());
        int joined = Math.max(0, tournament.getJoinedSlots());
        int pct = total <= 0 ? 0 : Math.min(100, Math.round((joined * 100f) / total));
        content.addView(metricRow("Registration", joined + "/" + total + " slots", pct + "% filled"));
        content.addView(metricRow("Metadata", tournament.getMode() + " • " + tournament.getMap(), tournament.getDate() + " " + tournament.getTime()));
        content.addView(metricRow("Live match status", "Room " + (tournament.isRoomReleased() ? "released" : "hidden"),
                tournament.getStatus() + " • " + tournament.getRegistrationStatus()));
    }

    private void renderParticipants() {
        content.addView(sectionHeader("Participants"));
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                content.removeAllViews();
                renderParticipantsHeader(snapshot.getChildrenCount());
                if (!snapshot.exists()) {
                    content.addView(stateView("No participants yet", "Participants will appear here after registration."));
                    return;
                }
                for (DataSnapshot child : snapshot.getChildren()) {
                    content.addView(row(child.getKey(), text(child.child("teamName").getValue(), "Solo"), text(child.child("status").getValue(), "REGISTERED")));
                }
            }
            @Override public void onCancelled(DatabaseError error) { content.addView(stateView("Error", error.getMessage())); }
        };
        listeners.put("participants", listener);
        FirebaseRepository.tournamentParticipants(tournamentId).addValueEventListener(listener);
        content.addView(stateView("Loading", "Fetching participant records…"));
    }

    private void renderParticipantsHeader(long count) { content.addView(metricRow("Participants", String.valueOf(count), "Firebase node: participants")); }
    private void renderTeams() {
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                content.removeAllViews();
                content.addView(sectionHeader("Teams"));
                if (!snapshot.exists()) { content.addView(stateView("No teams found", "Teams will appear when the teams node is populated.")); return; }
                for (DataSnapshot child : snapshot.getChildren()) {
                    Team team = Team.fromMap(child.getKey(), castMap(child.getValue()));
                    if (team == null) continue;
                    content.addView(row(team.getName(), "Members: " + team.getMemberCount(), team.getStatus()));
                }
            }
            @Override public void onCancelled(DatabaseError error) { content.addView(stateView("Error", error.getMessage())); }
        };
        listeners.put("teams", listener);
        FirebaseRepository.teams().addValueEventListener(listener);
        content.addView(stateView("Loading", "Reading teams from Firebase…"));
    }

    private void renderMatches(boolean schedule) {
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                content.removeAllViews();
                content.addView(sectionHeader(schedule ? "Schedule" : "Matches"));
                if (!snapshot.exists()) { content.addView(stateView("No matches", "No match nodes found for this tournament.")); return; }
                int live = 0, waiting = 0, completed = 0;
                for (DataSnapshot child : snapshot.getChildren()) {
                    Match match = Match.fromMap(child.getKey(), castMap(child.getValue()));
                    if (match == null || !tournamentId.equals(match.getTournamentId())) continue;
                    String st = match.getEffectiveStatus();
                    if (Match.LIVE.equals(st) || Match.ROOM_RELEASED.equals(st)) live++;
                    else if (Match.COMPLETED.equals(st)) completed++;
                    else waiting++;
                    content.addView(row(match.getName(), match.getMap() + " • " + match.getMode(), st));
                }
                content.addView(metricRow("Status counts", "Live " + live + ", Waiting " + waiting, "Completed " + completed));
            }
            @Override public void onCancelled(DatabaseError error) { content.addView(stateView("Error", error.getMessage())); }
        };
        listeners.put("matches", listener);
        FirebaseRepository.matches().addValueEventListener(listener);
        content.addView(stateView("Loading", "Fetching tournament matches…"));
    }

    private void renderLeaderboard() {
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                content.removeAllViews();
                content.addView(sectionHeader("Leaderboard"));
                DataSnapshot allTime = snapshot.child("allTime");
                if (!allTime.exists()) { content.addView(stateView("No leaderboard data", "Leaderboard entries are not available yet.")); return; }
                for (DataSnapshot child : allTime.getChildren()) {
                    content.addView(row(text(child.child("username").getValue(), child.getKey()),
                            "Matches " + text(child.child("matches").getValue(), "0"),
                            text(child.child("winning").getValue(), "0 PTS")));
                }
            }
            @Override public void onCancelled(DatabaseError error) { content.addView(stateView("Error", error.getMessage())); }
        };
        listeners.put("leaderboard", listener);
        FirebaseRepository.leaderboard().addValueEventListener(listener);
        content.addView(stateView("Loading", "Fetching leaderboard data…"));
    }

    private void renderResults() {
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                content.removeAllViews();
                content.addView(sectionHeader("Results"));
                boolean any = false;
                for (DataSnapshot child : snapshot.getChildren()) {
                    if (!tournamentId.equals(text(child.child("tournamentId").getValue(), ""))) continue;
                    any = true;
                    content.addView(row(text(child.child("username").getValue(), child.getKey()),
                            "Kills " + text(child.child("kills").getValue(), "0"),
                            text(child.child("points").getValue(), "0") + " pts"));
                }
                if (!any) content.addView(stateView("No results", "Published results for this tournament will show here."));
            }
            @Override public void onCancelled(DatabaseError error) { content.addView(stateView("Error", error.getMessage())); }
        };
        listeners.put("results", listener);
        FirebaseRepository.results().addValueEventListener(listener);
        content.addView(stateView("Loading", "Fetching tournament results…"));
    }

    private void renderRules() {
        content.addView(card("Rules", tournament.getRules()));
        content.addView(card("Safe admin controls", "Use settings to manage room visibility, status and match operations without changing restricted account data."));
    }

    private void renderAnnouncements() { renderSimpleNode("announcements", "Announcements", FirebaseRepository.announcements()); }
    private void renderReports() { renderSimpleNode("reports", "Reports", FirebaseRepository.reports()); }
    private void renderSupportIssues() { renderSimpleNode("support", "Support Issues", FirebaseRepository.support()); }
    private void renderActivity() { renderSimpleNode("activity", "Activity", FirebaseRepository.activityLogs()); }

    private void renderSettings() {
        content.addView(card("Tournament settings", "Status: " + tournament.getStatus() + "\nRegistration: " + tournament.getRegistrationStatus() + "\nRoom: " + (tournament.isRoomReleased() ? "Released" : "Hidden")));
        MaterialButton refresh = actionButton("Reload tournament", v -> loadTournament());
        content.addView(refresh);
    }

    private void renderSimpleNode(String key, String sectionLabel, com.google.firebase.database.DatabaseReference ref) {
        content.addView(sectionHeader(sectionLabel));
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                content.removeAllViews();
                content.addView(sectionHeader(sectionLabel));
                if (!snapshot.exists()) { content.addView(stateView("No data", "No Firebase records available in /" + ref.getKey() + ".")); return; }
                int shown = 0;
                for (DataSnapshot child : snapshot.getChildren()) {
                    if (shown++ >= 8) break;
                    content.addView(row(child.getKey(), text(child.child("title").getValue(), text(child.child("subject").getValue(), sectionLabel)), text(child.child("status").getValue(), "ACTIVE")));
                }
            }
            @Override public void onCancelled(DatabaseError error) { content.addView(stateView("Error", error.getMessage())); }
        };
        listeners.put(key, listener);
        ref.addValueEventListener(listener);
        content.addView(stateView("Loading", "Reading " + sectionLabel.toLowerCase(Locale.ROOT) + "…"));
    }

    private void removeListeners() {
        for (Map.Entry<String, ValueEventListener> e : listeners.entrySet()) {
            String key = e.getKey();
            ValueEventListener listener = e.getValue();
            if ("participants".equals(key)) FirebaseRepository.tournamentParticipants(tournamentId).removeEventListener(listener);
            else if ("teams".equals(key)) FirebaseRepository.teams().removeEventListener(listener);
            else if ("matches".equals(key)) FirebaseRepository.matches().removeEventListener(listener);
            else if ("leaderboard".equals(key)) FirebaseRepository.leaderboard().removeEventListener(listener);
            else if ("results".equals(key)) FirebaseRepository.results().removeEventListener(listener);
            else if ("announcements".equals(key)) FirebaseRepository.announcements().removeEventListener(listener);
            else if ("reports".equals(key)) FirebaseRepository.reports().removeEventListener(listener);
            else if ("support".equals(key)) FirebaseRepository.support().removeEventListener(listener);
            else if ("activity".equals(key)) FirebaseRepository.activityLogs().removeEventListener(listener);
        }
        listeners.clear();
    }

    private LinearLayout card(String heading, String body) {
        LinearLayout box = box();
        box.addView(label(heading, 16, Color.WHITE, true));
        if (body != null && !body.trim().isEmpty()) box.addView(label(body, 13, Color.rgb(170, 174, 186), false));
        return box;
    }

    private LinearLayout metricRow(String left, String center, String right) {
        LinearLayout row = box();
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(metric(left, 1f));
        row.addView(metric(center, 1f));
        row.addView(metric(right, 1f));
        return row;
    }

    private LinearLayout metric(String text, float weight) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(Color.rgb(28, 28, 35));
        wrap.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, weight);
        p.rightMargin = dp(8);
        wrap.setLayoutParams(p);
        wrap.addView(label(text, 13, Color.WHITE, true));
        return wrap;
    }

    private LinearLayout row(String title, String subtitle, String statusText) {
        LinearLayout row = box();
        row.addView(label(title, 15, Color.WHITE, true));
        row.addView(label(subtitle, 12, Color.rgb(166, 171, 184), false));
        row.addView(label(statusText, 11, Color.rgb(237, 47, 62), true));
        return row;
    }

    private LinearLayout stateView(String heading, String body) {
        LinearLayout box = box();
        box.addView(label(heading, 15, Color.WHITE, true));
        box.addView(label(body, 13, Color.rgb(166, 171, 184), false));
        return box;
    }

    private LinearLayout box() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.rgb(20, 20, 25));
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(10);
        box.setLayoutParams(p);
        return box;
    }

    private TextView sectionHeader(String text) { return label(text, 17, Color.WHITE, true); }
    private TextView label(String text, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL);
        return v;
    }
    private MaterialButton actionButton(String text, View.OnClickListener click) {
        MaterialButton b = new MaterialButton(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(237, 47, 62)));
        b.setOnClickListener(click);
        return b;
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) { return value instanceof Map ? (Map<String, Object>) value : new HashMap<>(); }
    private String text(Object value, String fallback) { return value == null ? fallback : String.valueOf(value); }
    private void showMessage(String msg) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        removeListeners();
        super.onDestroy();
    }
}
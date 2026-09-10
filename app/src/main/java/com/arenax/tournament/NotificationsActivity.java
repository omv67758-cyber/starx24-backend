package com.arenax.tournament;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.arenax.tournament.data.FirebaseRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * B40 — Notification inbox. Shows global announcements and notices targeted at this user
 * (room releases, delays, results, support replies), newest first, with local read tracking.
 */
public class NotificationsActivity extends AppCompatActivity {
    private LinearLayout list;
    private TextView empty;
    private ValueEventListener listener;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("notification_read_state", MODE_PRIVATE);
        buildScreen();
        loadNotifications();
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
        heading.addView(label("INBOX", 10, Color.rgb(237, 47, 62), true));
        heading.addView(label("Notifications", 21, Color.WHITE, true));
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView markAll = label("MARK ALL READ", 10, Color.rgb(237, 47, 62), true);
        markAll.setPadding(dp(10), dp(10), dp(10), dp(10));
        markAll.setOnClickListener(v -> markAllRead());
        top.addView(markAll, new LinearLayout.LayoutParams(-2, -2));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        empty = label("No notifications yet.", 15, Color.LTGRAY, false);
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

    private void loadNotifications() {
        if (!FirebaseRepository.isReady(this)) {
            toast("Live notifications are unavailable right now.");
            return;
        }
        String uid = FirebaseAuth.getInstance().getUid();
        listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<DataSnapshot> items = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    String targetUid = child.child("userId").getValue(String.class);
                    if (targetUid == null || targetUid.equals(uid)) items.add(child);
                }
                Collections.sort(items, (a, b) -> {
                    long ta = a.child("createdAt").getValue(Long.class) == null ? 0L : a.child("createdAt").getValue(Long.class);
                    long tb = b.child("createdAt").getValue(Long.class) == null ? 0L : b.child("createdAt").getValue(Long.class);
                    return Long.compare(tb, ta);
                });
                list.removeAllViews();
                // A17/B29 — pinned announcements relevant to this user surface above the regular inbox.
                FirebaseRepository.announcements().addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot announcementSnapshot) {
                        filterAnnouncementsForUser(announcementSnapshot, uid, relevant -> {
                            List<DataSnapshot> pinned = new ArrayList<>();
                            List<DataSnapshot> regularAnnouncements = new ArrayList<>();
                            for (DataSnapshot a : relevant) {
                                if (Boolean.TRUE.equals(a.child("pinned").getValue())) pinned.add(a);
                                else regularAnnouncements.add(a);
                            }
                            for (DataSnapshot a : pinned) list.addView(buildAnnouncementRow(a));
                            for (DataSnapshot a : regularAnnouncements) list.addView(buildAnnouncementRow(a));
                            for (DataSnapshot item : items) list.addView(buildRow(item));
                            empty.setVisibility(items.isEmpty() && relevant.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
                        });
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) {
                        for (DataSnapshot item : items) list.addView(buildRow(item));
                        empty.setVisibility(items.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
                    }
                });
            }
            @Override public void onCancelled(@NonNull DatabaseError error) { toast(error.getMessage()); }
        };
        FirebaseRepository.notifications().addValueEventListener(listener);
    }

    private interface AnnouncementFilterCallback { void onFiltered(List<DataSnapshot> relevant); }

    /**
     * A17 targeting follow-up — an ALL announcement is relevant to everyone. A TOURNAMENT
     * announcement is only relevant to a user registered as a participant of that tournament,
     * and a TEAM announcement only to that team's captain/members. Resolves each targeted
     * announcement against Firebase before handing back the filtered list, so a Tournament
     * Manager's per-tournament notice no longer reaches every player.
     */
    private void filterAnnouncementsForUser(DataSnapshot announcementSnapshot, String uid, AnnouncementFilterCallback callback) {
        List<DataSnapshot> all = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (DataSnapshot a : announcementSnapshot.getChildren()) {
            Long scheduledAt = a.child("scheduledAt").getValue(Long.class);
            Long expiresAt = a.child("expiresAt").getValue(Long.class);
            if (scheduledAt != null && scheduledAt > 0L && scheduledAt > now) continue;
            if (expiresAt != null && expiresAt > 0L && expiresAt <= now) continue;
            all.add(a);
        }
        if (all.isEmpty()) { callback.onFiltered(all); return; }
        List<DataSnapshot> relevant = Collections.synchronizedList(new ArrayList<>());
        int[] pending = {all.size()};
        for (DataSnapshot a : all) {
            String targetType = String.valueOf(a.child("targetType").getValue());
            String targetId = a.child("targetId").getValue(String.class);
            if (uid == null || !("TOURNAMENT".equals(targetType) || "TEAM".equals(targetType)) || targetId == null || targetId.isEmpty()) {
                relevant.add(a);
                if (--pending[0] == 0) callback.onFiltered(sortedByOriginalOrder(relevant, all));
                continue;
            }
            if ("TOURNAMENT".equals(targetType)) {
                FirebaseRepository.tournamentParticipants(targetId).child(uid).addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        if (snap.exists()) relevant.add(a);
                        if (--pending[0] == 0) callback.onFiltered(sortedByOriginalOrder(relevant, all));
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) {
                        if (--pending[0] == 0) callback.onFiltered(sortedByOriginalOrder(relevant, all));
                    }
                });
            } else {
                FirebaseRepository.teams().child(targetId).addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        boolean isCaptain = uid.equals(snap.child("captainId").getValue(String.class));
                        boolean isMember = snap.child("members").child(uid).exists();
                        if (isCaptain || isMember) relevant.add(a);
                        if (--pending[0] == 0) callback.onFiltered(sortedByOriginalOrder(relevant, all));
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) {
                        if (--pending[0] == 0) callback.onFiltered(sortedByOriginalOrder(relevant, all));
                    }
                });
            }
        }
    }

    /** Async per-item resolution above can finish out of order; restore the original snapshot order. */
    private List<DataSnapshot> sortedByOriginalOrder(List<DataSnapshot> relevant, List<DataSnapshot> original) {
        List<DataSnapshot> ordered = new ArrayList<>();
        synchronized (relevant) {
            for (DataSnapshot a : original) if (relevant.contains(a)) ordered.add(a);
        }
        return ordered;
    }

    private android.view.View buildAnnouncementRow(DataSnapshot item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.bg_card_red);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.bottomMargin = dp(10);
        row.setLayoutParams(rowParams);
        String title = String.valueOf(item.child("title").getValue());
        String message = String.valueOf(item.child("message").getValue());
        row.addView(label("📌  " + title, 15, Color.WHITE, true));
        TextView messageView = label(message, 13, Color.rgb(200, 200, 208), false);
        messageView.setPadding(0, dp(4), 0, dp(4));
        row.addView(messageView);
        return row;
    }

    private android.view.View buildRow(DataSnapshot item) {
        boolean read = prefs.getBoolean(item.getKey(), false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.bg_card);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.bottomMargin = dp(10);
        row.setLayoutParams(rowParams);
        if (!read) row.setBackgroundResource(R.drawable.bg_card_red);

        String title = String.valueOf(item.child("title").getValue());
        String message = String.valueOf(item.child("message").getValue());
        Long createdAt = item.child("createdAt").getValue(Long.class);
        TextView titleView = label((read ? "" : "●  ") + title, 15, Color.WHITE, true);
        TextView messageView = label(message, 13, Color.rgb(200, 200, 208), false);
        messageView.setPadding(0, dp(4), 0, dp(4));
        row.addView(titleView);
        row.addView(messageView);
        if (createdAt != null) {
            String time = new SimpleDateFormat("dd MMM, hh:mm a", Locale.ENGLISH).format(new java.util.Date(createdAt));
            row.addView(label(time, 10, Color.GRAY, false));
        }
        row.setOnClickListener(v -> {
            prefs.edit().putBoolean(item.getKey(), true).apply();
            loadNotifications();
        });
        return row;
    }

    private void markAllRead() {
        SharedPreferences.Editor editor = prefs.edit();
        for (int i = 0; i < list.getChildCount(); i++) {
            // Re-scan keys directly from Firebase snapshot handled on next load; simplest: clear + rely on read set below.
        }
        FirebaseRepository.notifications().get().addOnSuccessListener(snapshot -> {
            for (DataSnapshot child : snapshot.getChildren()) editor.putBoolean(child.getKey(), true);
            editor.apply();
            loadNotifications();
            toast("All marked as read.");
        });
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
        if (listener != null) FirebaseRepository.notifications().removeEventListener(listener);
        super.onDestroy();
    }
}

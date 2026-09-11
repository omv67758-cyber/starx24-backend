package com.arenax.tournament.data;

import android.content.Context;

import androidx.annotation.NonNull;

import com.arenax.tournament.model.Team;
import com.arenax.tournament.model.Tournament;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;

public final class FirebaseRepository {
    private FirebaseRepository() {}

    /** Returns true only when the Firebase SDK and generated options are usable. */
    public static boolean isReady(Context context) {
        return isFirebaseAvailable(context);
    }

    /** FirebaseAuth can throw when google-services metadata is incomplete; callers use this safely. */
    public static FirebaseUser currentUser() {
        try {
            return FirebaseAuth.getInstance().getCurrentUser();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static DatabaseReference root() {
        return FirebaseDatabase.getInstance().getReference();
    }

    /**
     * Checks and initializes the default Firebase app without allowing a
     * configuration problem to crash the login screen.
     */
    public static boolean isFirebaseAvailable(Context context) {
        try {
            Context appContext = context == null ? null : context.getApplicationContext();
            if (appContext == null) return false;
            if (FirebaseApp.getApps(appContext).isEmpty()
                    && FirebaseApp.initializeApp(appContext) == null) {
                return false;
            }
            FirebaseAuth.getInstance();
            FirebaseDatabase.getInstance();
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static DatabaseReference tournaments() {
        return root().child("tournaments");
    }

    public static DatabaseReference users() {
        return root().child("users");
    }

    public static DatabaseReference phoneIndex() {
        return root().child("phoneIndex");
    }

    /** Reserves a normalized phone number; Firebase rules reject a second owner. */
    public static Task<Void> reservePhone(String phone, String uid) {
        Map<String, Object> record = new HashMap<>();
        record.put("uid", uid);
        record.put("phone", phone);
        return phoneIndex().child(phone).setValue(record);
    }

    public static DatabaseReference gameModes() {
        return root().child("gameModes");
    }

    public static DatabaseReference appConfig() {
        return root().child("appConfig");
    }

    public static DatabaseReference notifications() {
        return root().child("notifications");
    }

    public static DatabaseReference withdrawals() { return root().child("withdrawals"); }
    public static DatabaseReference adminUsers() { return root().child("adminUsers"); }

    public static Task<Void> bootstrapMaster(String uid, String email) {
        Map<String, Object> record = new HashMap<>();
        record.put("uid", uid);
        record.put("email", email);
        record.put("role", "MASTER_CONTROL");
        record.put("enabled", true);
        record.put("status", "ACTIVE");
        record.put("createdAt", ServerValue.TIMESTAMP);
        record.put("updatedAt", ServerValue.TIMESTAMP);
        return adminUsers().child(uid).setValue(record);
    }

    /**
     * Server-authoritative, idempotent payment transition. A Firebase transaction
     * makes concurrent admins contend on the same status value; only PENDING can
    * become PAID, so a second approval cannot succeed.
     */
    public static Task<Void> markWithdrawalPaid(String withdrawalId, String note) {
        com.google.android.gms.tasks.TaskCompletionSource<Void> completion = new com.google.android.gms.tasks.TaskCompletionSource<>();
        withdrawals().child(withdrawalId).child("status").runTransaction(new com.google.firebase.database.Transaction.Handler() {
            @Override public com.google.firebase.database.Transaction.Result doTransaction(com.google.firebase.database.MutableData current) {
                if (!"PENDING".equals(String.valueOf(current.getValue()))) return com.google.firebase.database.Transaction.abort();
                current.setValue("PAID");
                return com.google.firebase.database.Transaction.success(current);
            }
            @Override public void onComplete(com.google.firebase.database.DatabaseError error, boolean committed, com.google.firebase.database.DataSnapshot snapshot) {
                if (error != null) { completion.setException(error.toException()); return; }
                if (!committed) { completion.setException(new IllegalStateException("Withdrawal is no longer pending")); return; }
                Map<String, Object> update = new HashMap<>();
                update.put("paidAt", ServerValue.TIMESTAMP);
                update.put("updatedAt", ServerValue.TIMESTAMP);
                update.put("handledBy", currentUser() == null ? "unknown" : currentUser().getUid());
                update.put("adminNote", note == null ? "" : note.trim());
                withdrawals().child(withdrawalId).updateChildren(update);
                logActivity("PAYMENT_APPROVED", withdrawalId, note);
                completion.setResult(null);
            }
        });
        return completion.getTask();
    }

    public static Task<Void> addUserNotification(String userId, String title, String message, String type) {
        Map<String, Object> notification = new HashMap<>();
        notification.put("userId", userId);
        notification.put("title", title);
        notification.put("message", message);
        notification.put("type", type == null ? "GENERAL" : type);
        notification.put("createdAt", ServerValue.TIMESTAMP);
        notification.put("read", false);
        return notifications().push().setValue(notification);
    }

    public static DatabaseReference results() {
        return root().child("results");
    }

    public static DatabaseReference leaderboard() {
        return root().child("leaderboard");
    }

    public static DatabaseReference rewards() {
        return root().child("rewards");
    }

    public static DatabaseReference banners() {
        return root().child("banners");
    }

    public static DatabaseReference faq() {
        return root().child("faq");
    }

    public static DatabaseReference support() {
        return root().child("support");
    }

    /** Creates a new support ticket owned by the current signed-in user. */
    public static Task<Void> createTicket(String category, String subject, String description) {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            return com.google.android.gms.tasks.Tasks.forException(
                    new IllegalStateException("Authentication required"));
        }
        String id = support().push().getKey();
        Map<String, Object> ticket = new HashMap<>();
        ticket.put("userId", uid);
        ticket.put("category", category);
        ticket.put("subject", subject);
        ticket.put("description", description);
        ticket.put("status", "OPEN");
        ticket.put("createdAt", ServerValue.TIMESTAMP);
        ticket.put("reply", "");
        return support().child(id == null ? "ticket" : id).setValue(ticket);
    }

    public static Task<Void> replyToTicket(String ticketId, String reply, String status) {
        Map<String, Object> update = new HashMap<>();
        update.put("reply", reply);
        update.put("status", status);
        update.put("repliedAt", ServerValue.TIMESTAMP);
        return support().child(ticketId).updateChildren(update);
    }

    public static DatabaseReference tournamentParticipants(String tournamentId) {
        return tournaments().child(tournamentId).child("participants");
    }

    public static DatabaseReference matchParticipants(String matchId) {
        return matches().child(matchId).child("participants");
    }

    /**
     * A19 — Targeted room-release push. Reads the uid keys under a
     * tournament's or match's "participants" node, looks up each one's
     * saved /users/{uid}/fcmToken, and hands the resulting token list to
     * the callback — never "All Users", only whoever actually joined.
     * Pass tournamentParticipants(id) or matchParticipants(id) as participantsRef.
     */
    public static void collectParticipantFcmTokens(DatabaseReference participantsRef, ParticipantTokensCallback callback) {
        participantsRef.addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
            @Override public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snapshot) {
                java.util.List<String> uids = new java.util.ArrayList<>();
                for (com.google.firebase.database.DataSnapshot child : snapshot.getChildren()) {
                    uids.add(child.getKey());
                }
                if (uids.isEmpty()) {
                    callback.onTokens(new java.util.ArrayList<>());
                    return;
                }
                java.util.List<String> tokens = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
                java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(uids.size());
                for (String uid : uids) {
                    users().child(uid).child("fcmToken").addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                        @Override public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot tokenSnap) {
                            String token = tokenSnap.getValue(String.class);
                            if (token != null && !token.isEmpty()) tokens.add(token);
                            if (remaining.decrementAndGet() == 0) callback.onTokens(tokens);
                        }
                        @Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError error) {
                            if (remaining.decrementAndGet() == 0) callback.onTokens(tokens);
                        }
                    });
                }
            }
            @Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError error) {
                callback.onTokens(new java.util.ArrayList<>());
            }
        });
    }

    public interface ParticipantTokensCallback {
        void onTokens(java.util.List<String> fcmTokens);
    }

    public static Task<Void> saveTournament(Tournament tournament) {
        String id = tournament.getId();
        if (id == null || id.startsWith("local-")) {
            id = tournaments().push().getKey();
        }
        return tournaments().child(id).setValue(tournament.toMap());
    }

    public static Task<Void> deleteTournament(String id) {
        return tournaments().child(id).removeValue();
    }

    public static Task<Void> saveUserProfile(String uid, String email, String name, String phone) {
        Map<String, Object> profile = new HashMap<>();
        profile.put("uid", uid);
        profile.put("email", email);
        profile.put("name", name);
        profile.put("phone", phone);
        profile.put("role", "USER");
        profile.put("createdAt", System.currentTimeMillis());
        profile.put("status", "ACTIVE");
        return users().child(uid).updateChildren(profile);
    }

    public static Task<Void> saveGameMode(String id, Map<String, Object> mode) {
        String key = id == null || id.trim().isEmpty() ? gameModes().push().getKey() : id;
        return gameModes().child(key == null ? "mode" : key).setValue(mode);
    }

    public static Task<Void> deleteGameMode(String id) {
        return gameModes().child(id).removeValue();
    }

    public static Task<Void> saveBanner(String id, Map<String, Object> banner) {
        String key = id == null || id.trim().isEmpty() ? banners().push().getKey() : id;
        return banners().child(key == null ? "banner" : key).setValue(banner);
    }

    public static Task<Void> deleteBanner(String id) {
        return banners().child(id).removeValue();
    }

    /**
     * Free registration only. No financial or payment data is part of this app. The Firebase rules still decide whether a user
     * may create this participant record.
     */
    public static Task<Void> registerForTournament(String tournamentId, String teamName, int teamSize) {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            return com.google.android.gms.tasks.Tasks.forException(
                    new IllegalStateException("Authentication required"));
        }
        Map<String, Object> participant = new HashMap<>();
        participant.put("userId", uid);
        participant.put("teamName", teamName == null ? "" : teamName.trim());
        participant.put("teamSize", teamSize);
        participant.put("status", "REGISTERED");
        participant.put("createdAt", ServerValue.TIMESTAMP);
        Task<Void> saved = tournamentParticipants(tournamentId).child(uid).setValue(participant);
        // A5/A6 follow-up — a newly registered player becomes a participant on every match already
        // scheduled under this tournament, so match-level room reads (see database.rules.json) work
        // for them immediately, without an admin having to re-add them per match.
        saved.addOnSuccessListener(v -> addUserToTournamentMatches(tournamentId, uid));
        return saved;
    }

    /**
     * A5/A6 follow-up — propagates one participant into the `participants` sub-node of every match
     * under a tournament. Called (a) right after a user registers, and (b) right after an admin
     * creates a new match under a tournament that already has registered participants, so the two
     * entry points stay in sync regardless of order.
     */
    private static void addUserToTournamentMatches(String tournamentId, String uid) {
        matches().orderByChild("tournamentId").equalTo(tournamentId)
                .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                    @Override public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snapshot) {
                        for (com.google.firebase.database.DataSnapshot match : snapshot.getChildren()) {
                            matches().child(match.getKey()).child("participants").child(uid).setValue(true);
                        }
                    }
                    @Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError error) { }
                });
    }

    /** Mirror of {@link #addUserToTournamentMatches}, run once for every already-registered participant. */
    private static void seedMatchParticipantsFromTournament(String tournamentId, String matchId) {
        tournamentParticipants(tournamentId).addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
            @Override public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snapshot) {
                for (com.google.firebase.database.DataSnapshot participant : snapshot.getChildren()) {
                    matches().child(matchId).child("participants").child(participant.getKey()).setValue(true);
                }
            }
            @Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError error) { }
        });
    }

    // ---------------------------------------------------------------------
    // A8 / B32 — Registration Waitlist. Free entry only; no payment involved.
    // Activates automatically once a tournament's slots are full.
    // ---------------------------------------------------------------------
    public static DatabaseReference tournamentWaitlist(String tournamentId) {
        return tournaments().child(tournamentId).child("waitlist");
    }

    /** Places the signed-in user on the waitlist. Safe to call only after the caller has
     *  confirmed the tournament is full; duplicate entries are prevented by keying on uid. */
    public static Task<Void> joinWaitlist(String tournamentId) {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            return com.google.android.gms.tasks.Tasks.forException(
                    new IllegalStateException("Authentication required"));
        }
        Map<String, Object> entry = new HashMap<>();
        entry.put("userId", uid);
        entry.put("status", "WAITLISTED");
        entry.put("createdAt", ServerValue.TIMESTAMP);
        return tournamentWaitlist(tournamentId).child(uid).setValue(entry);
    }

    /** Admin action — moves the longest-waiting entry into `participants` and removes it from the
     *  waitlist, so the slot counter and the promoted user's status update automatically. */
    public static Task<Void> promoteNextFromWaitlist(String tournamentId) {
        com.google.android.gms.tasks.TaskCompletionSource<Void> result =
                new com.google.android.gms.tasks.TaskCompletionSource<>();
        tournamentWaitlist(tournamentId).orderByChild("createdAt").limitToFirst(1)
                .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                    @Override public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snapshot) {
                        if (!snapshot.exists()) {
                            result.setException(new IllegalStateException("Waitlist is empty."));
                            return;
                        }
                        com.google.firebase.database.DataSnapshot first = snapshot.getChildren().iterator().next();
                        String uid = first.getKey();
                        Map<String, Object> participant = new HashMap<>();
                        participant.put("userId", uid);
                        participant.put("teamName", "");
                        participant.put("teamSize", 1);
                        participant.put("status", "REGISTERED");
                        participant.put("createdAt", ServerValue.TIMESTAMP);
                        tournamentParticipants(tournamentId).child(uid).setValue(participant)
                                .addOnSuccessListener(v -> tournamentWaitlist(tournamentId).child(uid).removeValue()
                                        .addOnSuccessListener(v2 -> {
                                            addUserToTournamentMatches(tournamentId, uid);
                                            result.setResult(null);
                                        })
                                        .addOnFailureListener(result::setException))
                                .addOnFailureListener(result::setException);
                    }
                    @Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError error) {
                        result.setException(error.toException());
                    }
                });
        return result.getTask();
    }

    public static Task<Void> publishNotification(String title, String message) {
        String id = notifications().push().getKey();
        Map<String, Object> notification = new HashMap<>();
        notification.put("title", title);
        notification.put("message", message);
        notification.put("createdAt", System.currentTimeMillis());
        notification.put("type", "GLOBAL");
        return notifications().child(id == null ? "notice" : id).setValue(notification);
    }

    // ---------------------------------------------------------------------
    // A17 — Announcement System with targeting, pin and expiry
    // ---------------------------------------------------------------------
    public static DatabaseReference announcements() {
        return root().child("announcements");
    }

    /**
     * targetType: ALL | TOURNAMENT | TEAM. targetId is required for TOURNAMENT/TEAM and ignored for ALL.
     * Always recorded in /announcements for the User App's announcement feed. Broadcast (ALL) announcements
     * also drop a /notifications record so the existing global Notification Center keeps working unchanged;
     * targeted ones are the foundation for future per-tournament/per-team push delivery.
     */
    public static Task<Void> publishAnnouncement(String title, String message, String targetType,
                                                  String targetId, boolean pinned, long expiresAt) {
        return publishAnnouncement(title, message, targetType, targetId, pinned, 0L, expiresAt);
    }

    /**
     * scheduledAt and expiresAt are epoch milliseconds. A zero value means "immediately" or
     * "never expires". The client filters inactive announcements after refresh, while the
     * existing notification fan-out is sent only for announcements that are active now.
     */
    public static Task<Void> publishAnnouncement(String title, String message, String targetType,
                                                  String targetId, boolean pinned, long scheduledAt,
                                                  long expiresAt) {
        String id = announcements().push().getKey();
        Map<String, Object> announcement = new HashMap<>();
        announcement.put("title", title);
        announcement.put("message", message);
        announcement.put("targetType", targetType);
        announcement.put("targetId", targetId == null ? "" : targetId);
        announcement.put("pinned", pinned);
        announcement.put("scheduledAt", scheduledAt);
        announcement.put("expiresAt", expiresAt);
        announcement.put("createdAt", ServerValue.TIMESTAMP);
        Task<Void> saved = announcements().child(id == null ? "announcement" : id).setValue(announcement);
        if ("ALL".equals(targetType) && (scheduledAt <= 0L || scheduledAt <= System.currentTimeMillis())) {
            publishNotification(title, message);
        }
        return saved;
    }

    public static Task<Void> deleteAnnouncement(String id) {
        return announcements().child(id).removeValue();
    }

    public static void signOut() {
        FirebaseAuth.getInstance().signOut();
    }

    // ---------------------------------------------------------------------
    // A9/B39 — Team Management
    // ---------------------------------------------------------------------
    public static DatabaseReference teams() {
        return root().child("teams");
    }

    /** Creates a team led by the current user. Matches database.rules.json: only the captain (or admin) can write it. */
    public static Task<Void> createTeam(String name, String displayName) {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            return com.google.android.gms.tasks.Tasks.forException(
                    new IllegalStateException("Authentication required"));
        }
        String id = teams().push().getKey();
        if (id == null) {
            return com.google.android.gms.tasks.Tasks.forException(new IllegalStateException("Could not create team id"));
        }
        Map<String, Object> team = new HashMap<>();
        team.put("name", name);
        team.put("logoUrl", "");
        team.put("captainId", uid);
        team.put("status", "ACTIVE");
        team.put("createdAt", System.currentTimeMillis());
        Map<String, Object> members = new HashMap<>();
        members.put(uid, displayName == null ? "Captain" : displayName);
        team.put("members", members);
        return teams().child(id).setValue(team);
    }

    /** Joins an existing team by its Team ID. Team object is fully owned by the captain, so this updates only the members child. */
    public static Task<Void> joinTeam(String teamId, String displayName) {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            return com.google.android.gms.tasks.Tasks.forException(
                    new IllegalStateException("Authentication required"));
        }
        return teams().child(teamId).child("members").child(uid)
                .setValue(displayName == null ? "Player" : displayName);
    }

    public static Task<Void> leaveTeam(String teamId) {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            return com.google.android.gms.tasks.Tasks.forException(
                    new IllegalStateException("Authentication required"));
        }
        return teams().child(teamId).child("members").child(uid).removeValue();
    }

    /** Admin-only: verify, disband or otherwise change a team's status (server rules enforce the admin claim). */
    public static Task<Void> setTeamStatus(String teamId, String status) {
        return teams().child(teamId).child("status").setValue(status);
    }

    public static Task<Void> disbandTeam(String teamId) {
        return teams().child(teamId).removeValue();
    }

    // ---------------------------------------------------------------------
    // A12/B36 — Match Results + A11/B37 Leaderboard
    // ---------------------------------------------------------------------
    /**
     * Admin enters kills + placement for a player in a tournament. Points are auto-calculated
     * from a simple configurable placement+kill table, written to /results and rolled into the
     * /leaderboard/allTime node so LeaderboardFragment updates live without further wiring.
     */
    public static Task<Void> publishResult(String tournamentId, String uid, String username,
                                            int kills, int placement) {
        if (kills < 0) {
            return com.google.android.gms.tasks.Tasks.forException(
                    new IllegalArgumentException("Kills cannot be negative."));
        }
        if (placement <= 0) {
            return com.google.android.gms.tasks.Tasks.forException(
                    new IllegalArgumentException("Placement must be greater than zero."));
        }
        int placementPoints = placementPoints(placement);
        int totalPoints = placementPoints + (kills * 1);
        String resultId = results().push().getKey();
        Map<String, Object> result = new HashMap<>();
        result.put("tournamentId", tournamentId);
        result.put("userId", uid);
        result.put("username", username);
        result.put("kills", kills);
        result.put("placement", placement);
        result.put("points", totalPoints);
        result.put("publishedAt", ServerValue.TIMESTAMP);
        if (resultId != null) results().child(resultId).setValue(result);

        DatabaseReference row = leaderboard().child("allTime").child(uid);
        return row.get().continueWithTask(task -> {
            long matches = 0, prevKills = 0, prevScore = 0;
            if (task.isSuccessful() && task.getResult() != null && task.getResult().exists()) {
                Object m = task.getResult().child("matches").getValue();
                Object k = task.getResult().child("kills").getValue();
                Object s = task.getResult().child("score").getValue();
                if (m instanceof Number) matches = ((Number) m).longValue();
                if (k instanceof Number) prevKills = ((Number) k).longValue();
                if (s instanceof Number) prevScore = ((Number) s).longValue();
            }
            Map<String, Object> update = new HashMap<>();
            update.put("username", username);
            update.put("matches", matches + 1);
            update.put("kills", prevKills + kills);
            update.put("score", prevScore + totalPoints);
            update.put("winning", (prevScore + totalPoints) + " PTS");
            update.put("updatedAt", ServerValue.TIMESTAMP);
            return row.updateChildren(update);
        });
    }

    /** Simple, transparent placement point table: 1st=15, 2nd=12, 3rd=10, 4th-10th sliding, else 1. */
    private static int placementPoints(int placement) {
        int[] table = {15, 12, 10, 8, 6, 4, 3, 2, 1, 1};
        if (placement >= 1 && placement <= table.length) return table[placement - 1];
        return placement > 0 ? 1 : 0;
    }

    // ---------------------------------------------------------------------
    // A13/B43 — Result Disputes
    // ---------------------------------------------------------------------
    public static DatabaseReference disputes() {
        return root().child("disputes");
    }

    public static Task<Void> submitDispute(String tournamentId, String tournamentTitle,
                                            String reason, String description) {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            return com.google.android.gms.tasks.Tasks.forException(
                    new IllegalStateException("Authentication required"));
        }
        String id = disputes().push().getKey();
        Map<String, Object> dispute = new HashMap<>();
        dispute.put("userId", uid);
        dispute.put("tournamentId", tournamentId);
        dispute.put("tournamentTitle", tournamentTitle);
        dispute.put("reason", reason);
        dispute.put("description", description);
        dispute.put("status", "PENDING");
        dispute.put("createdAt", ServerValue.TIMESTAMP);
        return disputes().child(id == null ? "dispute" : id).setValue(dispute);
    }

    /** Admin: PENDING -> UNDER_REVIEW -> RESOLVED | REJECTED | CLOSED. */
    public static Task<Void> updateDisputeStatus(String disputeId, String status, String adminNote) {
        Map<String, Object> update = new HashMap<>();
        update.put("status", status);
        update.put("adminNote", adminNote == null ? "" : adminNote);
        update.put("resolvedAt", ServerValue.TIMESTAMP);
        return disputes().child(disputeId).updateChildren(update);
    }

    // ---------------------------------------------------------------------
    // A14/B43 — Reports & Moderation
    // ---------------------------------------------------------------------
    public static DatabaseReference reports() {
        return root().child("reports");
    }

    public static Task<Void> submitReport(String targetType, String targetId, String targetLabel,
                                           String reason, String description) {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            return com.google.android.gms.tasks.Tasks.forException(
                    new IllegalStateException("Authentication required"));
        }
        String id = reports().push().getKey();
        Map<String, Object> report = new HashMap<>();
        report.put("userId", uid);
        report.put("targetType", targetType);
        report.put("targetId", targetId == null ? "" : targetId);
        report.put("targetLabel", targetLabel == null ? "" : targetLabel);
        report.put("reason", reason);
        report.put("description", description);
        report.put("status", "PENDING");
        report.put("createdAt", ServerValue.TIMESTAMP);
        return reports().child(id == null ? "report" : id).setValue(report);
    }

    public static Task<Void> updateReportStatus(String reportId, String status, String moderatorNote) {
        Map<String, Object> update = new HashMap<>();
        update.put("status", status);
        update.put("moderatorNote", moderatorNote == null ? "" : moderatorNote);
        update.put("resolvedAt", ServerValue.TIMESTAMP);
        return reports().child(reportId).updateChildren(update);
    }

    /** Admin moderation tool: WARN / RESTRICT / SUSPEND / BAN / ACTIVATE, written to users/{uid}/status. */
    public static Task<Void> setUserStatus(String uid, String status) {
        Map<String, Object> update = new HashMap<>();
        update.put("status", status);
        return users().child(uid).updateChildren(update);
    }

    // ---------------------------------------------------------------------
    // A16/B41 — FAQ Management
    // ---------------------------------------------------------------------
    public static Task<Void> saveFaq(String id, String question, String answer, String category) {
        String key = id == null || id.trim().isEmpty() ? faq().push().getKey() : id;
        Map<String, Object> entry = new HashMap<>();
        entry.put("question", question);
        entry.put("answer", answer);
        entry.put("category", category == null ? "General" : category);
        entry.put("createdAt", System.currentTimeMillis());
        entry.put("published", true);
        return faq().child(key == null ? "faq" : key).setValue(entry);
    }

    public static Task<Void> deleteFaq(String id) {
        return faq().child(id).removeValue();
    }

    // ---------------------------------------------------------------------
    // A1 — Admin RBAC: Super Admin / Tournament Manager / Support Agent / Moderator
    // ---------------------------------------------------------------------
    public static DatabaseReference adminRoles() {
        return root().child("admin_roles");
    }

    public static Task<Void> setAdminRole(String uid, String role) {
        Map<String, Object> record = new HashMap<>();
        record.put("role", role);
        record.put("assignedAt", ServerValue.TIMESTAMP);
        return adminRoles().child(uid).setValue(record);
    }

    // ---------------------------------------------------------------------
    // A5/A6/A7/B34/B35 — Match Management, Countdown, Room Control
    // ---------------------------------------------------------------------
    public static DatabaseReference matches() {
        return root().child("matches");
    }

    public static Task<Void> saveMatch(com.arenax.tournament.model.Match match) {
        String id = match.getId();
        boolean isNew = id == null || id.trim().isEmpty();
        if (isNew) id = matches().push().getKey();
        final String matchId = id == null ? "match" : id;
        Task<Void> saved = matches().child(matchId).setValue(match.toMap());
        // See addUserToTournamentMatches: keep a brand-new match's participant list in sync with
        // whoever is already registered for its tournament, so room access works without a
        // separate re-registration step.
        if (isNew && match.getTournamentId() != null && !match.getTournamentId().trim().isEmpty()) {
            saved.addOnSuccessListener(v -> seedMatchParticipantsFromTournament(match.getTournamentId(), matchId));
        }
        return saved;
    }

    public static Task<Void> deleteMatch(String matchId) {
        return matches().child(matchId).removeValue();
    }

    public static Task<Void> setMatchStatus(String matchId, String status) {
        return matches().child(matchId).child("status").setValue(status);
    }

    /** A7 — Release Room: writes room id/password + timestamp; user app updates instantly via listener. */
    public static Task<Void> releaseMatchRoom(String matchId, String roomId, String roomPassword) {
        Map<String, Object> update = new HashMap<>();
        update.put("roomId", roomId);
        update.put("roomPassword", roomPassword);
        update.put("roomReleased", true);
        update.put("roomReleasedAt", ServerValue.TIMESTAMP);
        update.put("status", com.arenax.tournament.model.Match.ROOM_RELEASED);
        update.put("delayReason", "");
        return matches().child(matchId).updateChildren(update);
    }

    public static Task<Void> hideMatchRoom(String matchId) {
        Map<String, Object> update = new HashMap<>();
        update.put("roomReleased", false);
        update.put("status", com.arenax.tournament.model.Match.WAITING);
        return matches().child(matchId).updateChildren(update);
    }

    /** A7 — Delay: sets reason + new expected time and flips status so the user sees "MATCH DELAYED". */
    public static Task<Void> setMatchDelay(String matchId, String reason, long newExpectedAt) {
        Map<String, Object> update = new HashMap<>();
        update.put("delayReason", reason == null ? "" : reason);
        update.put("delayExpectedAt", newExpectedAt);
        update.put("status", com.arenax.tournament.model.Match.DELAYED);
        return matches().child(matchId).updateChildren(update);
    }

    public static Task<Void> clearMatchDelay(String matchId) {
        Map<String, Object> update = new HashMap<>();
        update.put("delayReason", "");
        update.put("status", com.arenax.tournament.model.Match.WAITING);
        return matches().child(matchId).updateChildren(update);
    }

    // ---------------------------------------------------------------------
    // A22 — Admin Activity Log
    // ---------------------------------------------------------------------
    public static DatabaseReference activityLogs() {
        return root().child("activity_logs");
    }

    /** Fire-and-forget audit trail for every meaningful admin mutation. */
    public static void logActivity(String action, String target, String details) {
        String id = activityLogs().push().getKey();
        if (id == null) return;
        com.google.firebase.auth.FirebaseUser admin = currentUser();
        Map<String, Object> log = new HashMap<>();
        log.put("admin", admin == null ? "unknown" : (admin.getEmail() == null ? admin.getUid() : admin.getEmail()));
        log.put("action", action);
        log.put("target", target == null ? "" : target);
        log.put("details", details == null ? "" : details);
        log.put("timestamp", ServerValue.TIMESTAMP);
        activityLogs().child(id).setValue(log);
    }

    // ---------------------------------------------------------------------
    // Demo Data Requirement (master prompt) — one-tap realistic seed data for
    // Free Fire Max: categories, tournaments across every status, teams,
    // matches in every room state, a populated leaderboard, FAQ, an
    // announcement and both an open and a resolved support ticket. Uses a
    // single atomic multi-path update() from the database root (Firebase
    // RTDB treats "/"-joined keys passed to updateChildren as full paths),
    // so it either fully seeds or fails cleanly — never a half-written state.
    // Synthetic player/team ids are used for leaderboard + tickets since no
    // real auth accounts exist yet on a fresh project; nothing here touches
    // any payment/wallet/coin field, matching the prompt's hard constraint.
    // ---------------------------------------------------------------------
    public static Task<Void> seedDemoData() {
        Map<String, Object> update = new HashMap<>();
        long now = System.currentTimeMillis();
        long day = 24L * 60 * 60 * 1000;

        String[][] categories = {
                {"cat_solo", "Solo Showdown", "Every player for themselves."},
                {"cat_duo", "Duo Clash", "Pair up and push for the crown."},
                {"cat_squad", "Squad Battle", "Four-player squads, full map."},
                {"cat_clash", "Clash Squad", "Fast-paced 4v4 round-based mode."},
                {"cat_custom", "Custom Room", "Private custom lobbies."},
        };
        for (int i = 0; i < categories.length; i++) {
            Map<String, Object> cat = new HashMap<>();
            cat.put("title", categories[i][1]);
            cat.put("imageUrl", "");
            cat.put("description", categories[i][2]);
            cat.put("displayOrder", i);
            cat.put("createdAt", now);
            cat.put("active", true);
            update.put("gameModes/" + categories[i][0], cat);
        }

        String[] maps = {"Bermuda", "Purgatory", "Kalahari", "Alpine", "Nexterra"};
        // Each row: id, title, categoryId, mode, map, status, registrationStatus,
        // hoursFromNow (negative = already started/finished), totalSlots, joinedSlots, prizeInfo.
        Object[][] rows = {
                {"demo_t1", "Bermuda Solo Cup", "cat_solo", "SOLO", maps[0], "UPCOMING", "OPEN", 48, 60, 22, "500 Diamonds"},
                {"demo_t2", "Duo Purgatory Series", "cat_duo", "DUO", maps[1], "UPCOMING", "OPEN", 24, 40, 18, "Bundle + Badge"},
                {"demo_t3", "Kalahari Squad Wars", "cat_squad", "SQUAD", maps[2], "UPCOMING", "OPEN", 6, 20, 20, "1000 Diamonds"},
                {"demo_t4", "Alpine Clash Squad Rush", "cat_clash", "CLASH SQUAD", maps[3], "ONGOING", "CLOSED", -2, 16, 16, "Emote + Badge"},
                {"demo_t5", "Nexterra Booyah League", "cat_squad", "SQUAD", maps[4], "ONGOING", "CLOSED", -6, 20, 20, "1500 Diamonds"},
                {"demo_t6", "Bermuda Weekly Solo", "cat_solo", "SOLO", maps[0], "COMPLETED", "CLOSED", -72, 60, 60, "300 Diamonds"},
                {"demo_t7", "Purgatory Duo Finals", "cat_duo", "DUO", maps[1], "COMPLETED", "CLOSED", -96, 40, 40, "Winner Bundle"},
                {"demo_t8", "Kalahari Grand Slam", "cat_squad", "SQUAD", maps[2], "COMPLETED", "CLOSED", -120, 20, 20, "2000 Diamonds"},
        };
        java.text.SimpleDateFormat dateFmt = new java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.ENGLISH);
        java.text.SimpleDateFormat timeFmt = new java.text.SimpleDateFormat("hh:mm a", java.util.Locale.ENGLISH);
        for (Object[] r : rows) {
            String id = (String) r[0];
            long startAt = now + ((int) r[7]) * 60L * 60L * 1000L;
            java.util.Date startDate = new java.util.Date(startAt);
            Map<String, Object> tour = new HashMap<>();
            tour.put("title", r[1]);
            tour.put("mode", r[3]);
            tour.put("categoryId", r[2]);
            tour.put("bannerUrl", "");
            tour.put("description", "Official " + r[1] + " — check in early, read the rules tab, and be ready before the room drops.");
            tour.put("date", dateFmt.format(startDate));
            tour.put("time", timeFmt.format(startDate));
            tour.put("startAt", startAt);
            tour.put("registrationCloseAt", startAt - (30L * 60 * 1000));
            tour.put("roomReleaseAt", startAt - (10L * 60 * 1000));
            tour.put("prizeInfo", r[10]);
            tour.put("totalSlots", r[8]);
            tour.put("joinedSlots", r[9]);
            tour.put("teamSize", "SOLO".equals(r[3]) ? 1 : ("DUO".equals(r[3]) ? 2 : 4));
            tour.put("map", r[4]);
            tour.put("matchType", r[3]);
            tour.put("rules", "No teaming, no emulator abuse, no smurfing. Screenshot your result screen.");
            tour.put("prizeDistribution", "");
            tour.put("roomId", "COMPLETED".equals(r[5]) || "ONGOING".equals(r[5]) ? String.valueOf(100000 + id.hashCode() % 900000) : "");
            tour.put("roomPassword", "COMPLETED".equals(r[5]) || "ONGOING".equals(r[5]) ? "ff" + Math.abs(id.hashCode() % 1000) : "");
            tour.put("roomReleased", "COMPLETED".equals(r[5]) || "ONGOING".equals(r[5]));
            tour.put("delayReason", "");
            tour.put("status", r[5]);
            tour.put("registrationStatus", r[6]);
            tour.put("active", true);
            tour.put("accentColor", android.graphics.Color.rgb(237, 47, 62));
            update.put("tournaments/" + id, tour);
        }

        String[][] teams = {
                {"demo_team1", "Ghost Riders"}, {"demo_team2", "Crimson Wolves"},
                {"demo_team3", "Neon Sharks"}, {"demo_team4", "Iron Falcons"},
                {"demo_team5", "Shadow Kings"}, {"demo_team6", "Blaze Squad"},
        };
        for (String[] team : teams) {
            Map<String, Object> t = new HashMap<>();
            t.put("name", team[1]);
            t.put("logoUrl", "");
            t.put("captainId", "demo_captain_" + team[0]);
            t.put("status", "ACTIVE");
            t.put("createdAt", now);
            Map<String, Object> members = new HashMap<>();
            members.put("demo_captain_" + team[0], team[1] + " (Captain)");
            t.put("members", members);
            update.put("teams/" + team[0], t);
        }

        // Matches under demo_t4 (ONGOING) covering every room state the prompt calls out.
        Map<String, Object> liveMatch = new HashMap<>();
        liveMatch.put("tournamentId", "demo_t4");
        liveMatch.put("tournamentTitle", "Alpine Clash Squad Rush");
        liveMatch.put("name", "Round 1 — Group A");
        liveMatch.put("round", 1);
        liveMatch.put("map", maps[3]);
        liveMatch.put("mode", "CLASH SQUAD");
        liveMatch.put("scheduledAt", now - (30L * 60 * 1000));
        liveMatch.put("maxSlots", 16);
        liveMatch.put("rules", "Best of 3 rounds, no revives.");
        liveMatch.put("status", "LIVE");
        liveMatch.put("roomId", "482913");
        liveMatch.put("roomPassword", "ff482");
        liveMatch.put("roomReleased", true);
        liveMatch.put("roomReleasedAt", now - (35L * 60 * 1000));
        liveMatch.put("delayReason", "");
        liveMatch.put("delayExpectedAt", 0L);
        update.put("matches/demo_m_live", liveMatch);

        Map<String, Object> waitingMatch = new HashMap<>();
        waitingMatch.put("tournamentId", "demo_t1");
        waitingMatch.put("tournamentTitle", "Bermuda Solo Cup");
        waitingMatch.put("name", "Qualifier — Round 1");
        waitingMatch.put("round", 1);
        waitingMatch.put("map", maps[0]);
        waitingMatch.put("mode", "SOLO");
        waitingMatch.put("scheduledAt", now - (2L * 60 * 1000));
        waitingMatch.put("maxSlots", 48);
        waitingMatch.put("rules", "Solo only, no teaming.");
        waitingMatch.put("status", "WAITING");
        waitingMatch.put("roomId", "");
        waitingMatch.put("roomPassword", "");
        waitingMatch.put("roomReleased", false);
        waitingMatch.put("roomReleasedAt", 0L);
        waitingMatch.put("delayReason", "");
        waitingMatch.put("delayExpectedAt", 0L);
        update.put("matches/demo_m_waiting", waitingMatch);

        Map<String, Object> releasedMatch = new HashMap<>();
        releasedMatch.put("tournamentId", "demo_t2");
        releasedMatch.put("tournamentTitle", "Duo Purgatory Series");
        releasedMatch.put("name", "Round 2 — Group B");
        releasedMatch.put("round", 2);
        releasedMatch.put("map", maps[1]);
        releasedMatch.put("mode", "DUO");
        releasedMatch.put("scheduledAt", now + (10L * 60 * 1000));
        releasedMatch.put("maxSlots", 40);
        releasedMatch.put("rules", "Duo only, both members must be registered.");
        releasedMatch.put("status", "ROOM_RELEASED");
        releasedMatch.put("roomId", "657204");
        releasedMatch.put("roomPassword", "ff657");
        releasedMatch.put("roomReleased", true);
        releasedMatch.put("roomReleasedAt", now - (1L * 60 * 1000));
        releasedMatch.put("delayReason", "");
        releasedMatch.put("delayExpectedAt", 0L);
        update.put("matches/demo_m_released", releasedMatch);

        // Populated leaderboard — 20 demo players with realistic score spread.
        String[] names = {
                "ShadowReaper", "NightHawk_FF", "ProGamerX", "BlazeQueen", "IronViper",
                "GhostSniper", "TurboKill", "SilentAssassin", "NovaStrike", "CrimsonAce",
                "DesertWolf", "SkyFallHero", "ZeroCool99", "VenomStrike", "RapidFireX",
                "PhantomEdge", "StormBreaker", "IceColdKiller", "RogueSquad", "ApexPredatorFF",
        };
        for (int i = 0; i < names.length; i++) {
            int kills = 180 - (i * 6);
            int matches = 40 - i;
            int score = 900 - (i * 30);
            Map<String, Object> row = new HashMap<>();
            row.put("username", names[i]);
            row.put("matches", matches);
            row.put("kills", kills);
            row.put("score", score);
            row.put("winning", score + " PTS");
            row.put("updatedAt", now);
            update.put("leaderboard/allTime/demo_player_" + i, row);
        }

        // FAQ
        String[][] faqs = {
                {"demo_faq1", "How do I join a tournament?", "Open the tournament, read the rules, and tap Register — all tournaments are free entry."},
                {"demo_faq2", "When do I get the Room ID and Password?", "Room details unlock shortly before the scheduled start once the admin releases them. You'll see \"WAIT FOR ROOM ID & PASSWORD\" until then."},
                {"demo_faq3", "What do I do if a match is delayed?", "The match card will show \"MATCH DELAYED\" with the reason — keep the app open, no action needed from you."},
                {"demo_faq4", "How are points calculated?", "Points combine your placement and kills using the tournament's published points table, shown on the Leaderboard tab."},
        };
        for (int i = 0; i < faqs.length; i++) {
            Map<String, Object> f = new HashMap<>();
            f.put("question", faqs[i][1]);
            f.put("answer", faqs[i][2]);
            f.put("order", i);
            f.put("published", true);
            update.put("faq/" + faqs[i][0], f);
        }

        // Announcement (targeted: ALL)
        Map<String, Object> announcement = new HashMap<>();
        announcement.put("title", "Alpine Clash Squad Rush is LIVE now!");
        announcement.put("message", "Round 1 rooms are live — jump into My Matches to grab your Room ID and Password.");
        announcement.put("target", "ALL");
        announcement.put("targetId", "");
        announcement.put("pinned", true);
        announcement.put("createdAt", now);
        update.put("announcements/demo_ann1", announcement);

        // Support tickets: one open, one resolved.
        Map<String, Object> openTicket = new HashMap<>();
        openTicket.put("uid", "demo_user_1");
        openTicket.put("category", "Room ID/Password");
        openTicket.put("subject", "Room details not showing");
        openTicket.put("description", "My match started 5 minutes ago but I still don't see the room card.");
        openTicket.put("status", "OPEN");
        openTicket.put("createdAt", now - (10L * 60 * 1000));
        update.put("support/demo_ticket_open", openTicket);

        Map<String, Object> resolvedTicket = new HashMap<>();
        resolvedTicket.put("uid", "demo_user_2");
        resolvedTicket.put("category", "Result Problem");
        resolvedTicket.put("subject", "My kills weren't counted");
        resolvedTicket.put("description", "I had 6 kills in the Bermuda Weekly Solo but the leaderboard shows fewer.");
        resolvedTicket.put("status", "RESOLVED");
        resolvedTicket.put("createdAt", now - (3L * day));
        update.put("support/demo_ticket_resolved", resolvedTicket);

        return root().updateChildren(update);
    }
}

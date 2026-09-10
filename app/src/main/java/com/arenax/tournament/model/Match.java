package com.arenax.tournament.model;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * A5/A6/A7/B34/B35 — a scheduled match belongs to one tournament (a tournament can have many
 * rounds/matches). Countdown + room release are computed per match, not per tournament, so a
 * multi-round tournament can run several matches with independent rooms and statuses.
 */
public class Match implements Serializable {
    public static final String UPCOMING = "UPCOMING";
    public static final String WAITING = "WAITING";
    public static final String ROOM_RELEASED = "ROOM_RELEASED";
    public static final String LIVE = "LIVE";
    public static final String DELAYED = "DELAYED";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";

    private final String id;
    private final String tournamentId;
    private final String tournamentTitle;
    private final String name;
    private final int round;
    private final String map;
    private final String mode;
    private final long scheduledAt;
    private final int maxSlots;
    private final String rules;
    private final String status;
    private final String roomId;
    private final String roomPassword;
    private final boolean roomReleased;
    private final long roomReleasedAt;
    private final String delayReason;
    private final long delayExpectedAt;

    public Match(String id, String tournamentId, String tournamentTitle, String name, int round,
                 String map, String mode, long scheduledAt, int maxSlots, String rules,
                 String status, String roomId, String roomPassword, boolean roomReleased,
                 long roomReleasedAt, String delayReason, long delayExpectedAt) {
        this.id = id;
        this.tournamentId = tournamentId;
        this.tournamentTitle = tournamentTitle;
        this.name = name;
        this.round = round;
        this.map = map;
        this.mode = mode;
        this.scheduledAt = scheduledAt;
        this.maxSlots = maxSlots;
        this.rules = rules == null ? "" : rules;
        this.status = status == null ? UPCOMING : status;
        this.roomId = roomId == null ? "" : roomId;
        this.roomPassword = roomPassword == null ? "" : roomPassword;
        this.roomReleased = roomReleased;
        this.roomReleasedAt = roomReleasedAt;
        this.delayReason = delayReason == null ? "" : delayReason;
        this.delayExpectedAt = delayExpectedAt;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map1 = new HashMap<>();
        map1.put("tournamentId", tournamentId);
        map1.put("tournamentTitle", tournamentTitle);
        map1.put("name", name);
        map1.put("round", round);
        map1.put("map", map);
        map1.put("mode", mode);
        map1.put("scheduledAt", scheduledAt);
        map1.put("maxSlots", maxSlots);
        map1.put("rules", rules);
        map1.put("status", status);
        map1.put("roomId", roomId);
        map1.put("roomPassword", roomPassword);
        map1.put("roomReleased", roomReleased);
        map1.put("roomReleasedAt", roomReleasedAt);
        map1.put("delayReason", delayReason);
        map1.put("delayExpectedAt", delayExpectedAt);
        return map1;
    }

    public static Match fromMap(String id, Map<String, Object> v) {
        if (v == null) return null;
        return new Match(id,
                text(v.get("tournamentId")), text(v.get("tournamentTitle")),
                text(v.get("name")), number(v.get("round"), 1),
                text(v.get("map")), text(v.get("mode")),
                longNum(v.get("scheduledAt")), number(v.get("maxSlots"), 0),
                text(v.get("rules")), text(v.get("status")),
                text(v.get("roomId")), text(v.get("roomPassword")),
                Boolean.TRUE.equals(v.get("roomReleased")), longNum(v.get("roomReleasedAt")),
                text(v.get("delayReason")), longNum(v.get("delayExpectedAt")));
    }

    private static String text(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long longNum(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0L; }
    private static int number(Object o, int fb) { return o instanceof Number ? ((Number) o).intValue() : fb; }

    public String getId() { return id; }
    public String getTournamentId() { return tournamentId; }
    public String getTournamentTitle() { return tournamentTitle; }
    public String getName() { return name; }
    public int getRound() { return round; }
    public String getMap() { return map; }
    public String getMode() { return mode; }
    public long getScheduledAt() { return scheduledAt; }
    public int getMaxSlots() { return maxSlots; }
    public String getRules() { return rules; }
    public String getStatus() { return status; }
    public String getRoomId() { return roomId; }
    public String getRoomPassword() { return roomPassword; }
    public boolean isRoomReleased() { return roomReleased; }
    public long getRoomReleasedAt() { return roomReleasedAt; }
    public String getDelayReason() { return delayReason; }
    public boolean isDelayed() { return DELAYED.equals(status) || !delayReason.isEmpty(); }
    public long getDelayExpectedAt() { return delayExpectedAt; }

    /** Derived, real-time-safe status: countdown auto-flips to WAITING at zero without any write. */
    public String getEffectiveStatus() {
        if (CANCELLED.equals(status) || COMPLETED.equals(status) || LIVE.equals(status) || DELAYED.equals(status)) {
            return status;
        }
        if (roomReleased) return ROOM_RELEASED;
        if (scheduledAt > 0 && System.currentTimeMillis() >= scheduledAt) return WAITING;
        return UPCOMING;
    }
}

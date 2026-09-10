package com.arenax.tournament.model;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/** A9 — Team Management. Matches the existing database.rules.json "teams" schema:
 *  captainId (owner, only writer besides admin) + members map (uid -> display name). */
public class Team implements Serializable {
    private final String id;
    private final String name;
    private final String logoUrl;
    private final String captainId;
    private final String status;
    private final long createdAt;
    private final Map<String, String> members;

    public Team(String id, String name, String logoUrl, String captainId, String status,
                long createdAt, Map<String, String> members) {
        this.id = id;
        this.name = name;
        this.logoUrl = logoUrl == null ? "" : logoUrl;
        this.captainId = captainId;
        this.status = status == null || status.isEmpty() ? "ACTIVE" : status;
        this.createdAt = createdAt;
        this.members = members == null ? new HashMap<>() : members;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("name", name);
        map.put("logoUrl", logoUrl);
        map.put("captainId", captainId);
        map.put("status", status);
        map.put("createdAt", createdAt);
        map.put("members", members);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static Team fromMap(String id, Map<String, Object> value) {
        if (value == null) return null;
        Map<String, String> memberMap = new HashMap<>();
        Object rawMembers = value.get("members");
        if (rawMembers instanceof Map) {
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) rawMembers).entrySet()) {
                memberMap.put(entry.getKey(), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
            }
        }
        Object createdRaw = value.get("createdAt");
        long created = createdRaw instanceof Number ? ((Number) createdRaw).longValue() : 0L;
        return new Team(id,
                text(value.get("name"), "Unnamed Team"),
                text(value.get("logoUrl"), ""),
                text(value.get("captainId"), ""),
                text(value.get("status"), "ACTIVE"),
                created, memberMap);
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getLogoUrl() { return logoUrl; }
    public String getCaptainId() { return captainId; }
    public String getStatus() { return status; }
    public long getCreatedAt() { return createdAt; }
    public Map<String, String> getMembers() { return members; }
    public int getMemberCount() { return members.size(); }
}

package no.novari.qliktosharepoint.cache;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class EntraCache {

    private final Map<String, String> guestIdByEmail = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> groupMemberIds = new ConcurrentHashMap<>();
    private final Map<String, String> groupIdByDisplayName = new ConcurrentHashMap<>();

    @Getter
    private volatile Instant lastRefresh = Instant.EPOCH;

    public void clearAll() {
        guestIdByEmail.clear();
        groupMemberIds.clear();
        groupIdByDisplayName.clear();

    }

    public void putGroupIdByDisplayName(String displayName, String groupId) {
        if (displayName == null) return;
        String key = displayName.trim();
        if (key.isEmpty()) return;
        groupIdByDisplayName.put(key, groupId);
    }

    public String getGroupIdByDisplayName(String displayName) {
        if (displayName == null) return null;
        return groupIdByDisplayName.get(displayName.trim());
    }

    public void putGuest(String email, String userId) {
        if (email == null || userId == null) return;
        String e = email.trim().toLowerCase();
        if (e.isBlank()) return;
        guestIdByEmail.put(e, userId);
    }

    public String getGuestIdByEmail(String email) {
        if (email == null) return null;
        return guestIdByEmail.get(email.trim().toLowerCase());
    }

    public void setGroupMembers(String groupId, Set<String> memberIds) {
        if (groupId == null || groupId.isBlank()) return;
        groupMemberIds.put(groupId, memberIds);
    }

    public Set<String> getGroupMembers(String groupId) {
        if (groupId == null || groupId.isBlank()) return null;
        return groupMemberIds.get(groupId);
    }

    public void addMemberToGroup(String groupId, String userId) {
        if (groupId == null || groupId.isBlank() || userId == null || userId.isBlank()) return;
        groupMemberIds.computeIfAbsent(groupId, k -> ConcurrentHashMap.newKeySet()).add(userId);
    }

    public void removeMemberFromGroup(String groupId, String userId) {
        if (groupId == null || groupId.isBlank() || userId == null || userId.isBlank()) return;
        Set<String> set = groupMemberIds.get(groupId);
        if (set != null) set.remove(userId);
    }

    public void markRefreshed() {
        lastRefresh = Instant.now();
    }
}

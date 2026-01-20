package no.novari.qliktosharepoint.service;

import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.graph.models.ReferenceCreate;
import com.microsoft.kiota.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import no.novari.qliktosharepoint.cache.EntraCache;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GraphGroupService {

    private final GraphServiceClient graph;
    private final EntraCache cache;

    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private final Semaphore graphCallLimit = new Semaphore(6);

    private static final String QLIK_PREFIX = "Qlik-";

    private static String toEntraGroupName(String name) {
        if (name == null) return null;
        String s = name.trim();
        if (s.isEmpty()) return s;
        return s.startsWith(QLIK_PREFIX) ? s : QLIK_PREFIX + s;
    }


    public record MembershipSyncResult(int groups, int added, int skipped, int failed) {
        public static MembershipSyncResult empty() { return new MembershipSyncResult(0,0,0,0); }
    }

    public CompletableFuture<MembershipSyncResult> ensureUserInGroupsAsync(
            String userId, Collection<String> groupDisplayNames) {

        if (userId == null || userId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("userId is blank"));
        }
        if (groupDisplayNames == null || groupDisplayNames.isEmpty()) {
            return CompletableFuture.completedFuture(MembershipSyncResult.empty());
        }

        List<String> groups = groupDisplayNames.stream()
                .filter(Objects::nonNull)
                .map(GraphGroupService::toEntraGroupName)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();

        if (groups.isEmpty()) return CompletableFuture.completedFuture(MembershipSyncResult.empty());

        return CompletableFuture.supplyAsync(() -> {
            int added = 0, skipped = 0, failed = 0;

            for (String groupName : groups) {
                String groupId = cache.getGroupIdByDisplayName(groupName);
                if (groupId == null || groupId.isBlank()) {
                    failed++;
                    log.warn("Group not found in EntraCache groupName='{}' userId={}", groupName, userId);
                    continue;
                }

                Set<String> members = cache.getGroupMembers(groupId);
                if (members != null && members.contains(userId)) {
                    skipped++;
                    continue;
                }

                acquirePermit();
                try {
                    addUserToGroup(userId, groupId);
                    cache.addMemberToGroup(groupId, userId);
                    added++;
                } catch (Exception e) {
                    failed++;
                    log.error("FAILED membership userId={} -> groupName='{}' groupId={} cause={}",
                            userId, groupName, groupId, e.getMessage());
                } finally {
                    graphCallLimit.release();
                }
            }

            if (failed > 0) {
                log.warn("Membership sync done userId={} groups={} added={} skipped={} failed={}",
                        userId, groups.size(), added, skipped, failed);
            } else {
                log.debug("Membership sync done userId={} groups={} added={} skipped={} failed={}",
                        userId, groups.size(), added, skipped, failed);
            }

            return new MembershipSyncResult(groups.size(), added, skipped, failed);
        }, executor);
    }


    private void acquirePermit() {
        try {
            graphCallLimit.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for Graph call permit", e);
        }
    }

    private void addUserToGroup(String userId, String groupId) {
        try {
            ReferenceCreate ref = new ReferenceCreate();
            ref.setOdataId("https://graph.microsoft.com/v1.0/directoryObjects/" + userId);

            graph.groups()
                    .byGroupId(groupId)
                    .members()
                    .ref()
                    .post(ref);

        } catch (ApiException e) {
            if (e.getResponseStatusCode() == 400
                    && e.getMessage() != null
                    && e.getMessage().contains("object references already exist")) {
                cache.addMemberToGroup(groupId, userId);
                return;
            }
            throw new RuntimeException("Graph add member failed userId=" + userId + " groupId=" + groupId + ": " + e.getMessage(), e);
        }
    }

    public CompletableFuture<Void> removeUserFromGroupAsync(String userId, String groupId) {
        return CompletableFuture.runAsync(() -> graph.groups()
                .byGroupId(groupId)
                .members()
                .byDirectoryObjectId(userId)
                .ref()
                .delete(), executor);
    }
}

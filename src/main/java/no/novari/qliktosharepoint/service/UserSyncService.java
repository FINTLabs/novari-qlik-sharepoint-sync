package no.novari.qliktosharepoint.service;

import com.microsoft.kiota.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import no.novari.qliktosharepoint.cache.EntraCache;
import no.novari.qliktosharepoint.config.GraphProperties;
import no.novari.qliktosharepoint.config.QlikProperties;
import no.novari.qliktosharepoint.qlik.QlikUserClient;
import no.novari.qliktosharepoint.qlik.QlikUserDto;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserSyncService {

    private final QlikUserClient qlikUserClient;
    private final GraphUserService graphUserService;
    private final GraphGroupService graphGroupService;
    private final QlikToAadGroupMappingService mappingService;
    private final QlikProperties qlikProperties;
    private final GraphProperties graphProperties;
    private final EntraCache entraCache;
    private final ExecutorService executor = Executors.newFixedThreadPool(24);
    private final Semaphore inviteLimit = new Semaphore(6);
    private final Semaphore addLimit = new Semaphore(16);

    @PostConstruct
    public void logConfigAtStartup() {
        List<String> excluded = qlikProperties.getExcludedEmailDomains();
        if (excluded == null || excluded.isEmpty()) {
            log.info("No excluded email domains configured");
        } else {
            log.info("Excluded email domains configured (used only for import-filter): {}", excluded);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    public void syncAll() {
        List<QlikUserDto> users = qlikUserClient.getAllUsers();
        if (users == null) {
            log.warn("Skipping sync/reconcile because Qlik fetch failed. Will retry next run.");
            return;
        }

        Set<String> managedGroupNames = getManagedGroupNamesFromConfig();
        Desired desired = buildDesired(users, managedGroupNames);

        int usersFound = desired.desiredGroupsByEmail.size();
        int groupsFound = desired.groupsToUse.size();

        if (usersFound == 0) {
            log.info("No users to sync after filters. Done. usersFound=0 groupsFound={}", groupsFound);
            return;
        }

        Map<String, String> groupIdByName = resolveGroupIdsFromCache(desired.groupsToUse);
        Map<String, String> userIdByEmail = ensureGuestsAsync(desired.desiredGroupsByEmail.keySet(), desired.displayNameByEmail);

        AtomicInteger added = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        runMembershipsAsync(desired.desiredGroupsByEmail, userIdByEmail, groupIdByName, added, skipped, failed);

        if (qlikProperties.isCleanupRemoveMemberships()) {
            Map<String, Set<String>> desiredMembersByGroupId = buildDesiredMembersByGroupId(desired.desiredGroupsByEmail, userIdByEmail, groupIdByName);
            reconcileGroups(desired.groupsToUse, desiredMembersByGroupId, groupIdByName);
        } else {
            log.warn("Cleanup/reconcile disabled by config. To allow cleanup, enable this in config");
        }

        log.info("Sync summary usersFound={} groupsFound={} added={} skipped={} failed={}",
                usersFound, groupsFound, added.get(), skipped.get(), failed.get());
    }

    private Desired buildDesired(List<QlikUserDto> users, Set<String> managedGroupNames) {
        Set<String> observedGroupNames = new HashSet<>();

        Map<String, Set<String>> desiredGroupsByEmail = new HashMap<>();
        Map<String, String> displayNameByEmail = new HashMap<>();

        for (QlikUserDto u : users) {
            if (!isFederatedUser(u)) continue;

            String email = normalizeEmail(u.getEmail());
            if (email == null) continue;
            if (isExcludedDomain(email)) continue;

            Set<String> targetGroupNames = mappingService.resolveTargetAadGroupNames(u);
            if (targetGroupNames == null || targetGroupNames.isEmpty()) continue;

            Set<String> effectiveGroups = managedGroupNames.isEmpty()
                    ? new HashSet<>(targetGroupNames)
                    : targetGroupNames.stream().filter(managedGroupNames::contains).collect(Collectors.toSet());

            if (effectiveGroups.isEmpty()) continue;

            observedGroupNames.addAll(effectiveGroups);

            desiredGroupsByEmail.merge(email, effectiveGroups, (a, b) -> {
                a.addAll(b);
                return a;
            });

            if (u.getName() != null && !u.getName().isBlank()) {
                displayNameByEmail.putIfAbsent(email, u.getName());
            }
        }

        Set<String> groupsToUse = !managedGroupNames.isEmpty() ? managedGroupNames : observedGroupNames;
        return new Desired(desiredGroupsByEmail, displayNameByEmail, groupsToUse);
    }

    private Map<String, String> resolveGroupIdsFromCache(Set<String> groupsToUse) {
        Map<String, String> groupIdByName = new HashMap<>();
        for (String groupName : groupsToUse) {
            String groupId = entraCache.getGroupIdByDisplayName(groupName);
            if (groupId != null && !groupId.isBlank()) {
                groupIdByName.put(groupName, groupId);
            } else {
                log.warn("Missing groupId in cache for groupName='{}' (will be skipped)", groupName);
            }
        }
        return groupIdByName;
    }

    private Map<String, String> ensureGuestsAsync(Set<String> emails, Map<String, String> displayNameByEmail) {
        Map<String, String> userIdByEmail = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        int cachedGuests = 0;
        int toCreate = 0;

        for (String email : emails) {
            String cachedId = entraCache.getGuestIdByEmail(email);
            if (cachedId != null && !cachedId.isBlank()) {
                userIdByEmail.put(email, cachedId);
                cachedGuests++;
                continue;
            }

            toCreate++;
            CompletableFuture<Void> f = CompletableFuture
                    .supplyAsync(() -> {
                        acquire(inviteLimit);
                        try {
                            String displayName = displayNameByEmail.getOrDefault(email, email);
                            return withRetry("ensureGuest", email, () -> graphUserService.ensureGuestUserId(email, displayName));
                        } finally {
                            inviteLimit.release();
                        }
                    }, executor)
                    .orTimeout(10, TimeUnit.MINUTES)
                    .thenAccept(userId -> {
                        userIdByEmail.put(email, userId);
                        entraCache.putGuest(email, userId);
                    })
                    .whenComplete((_, ex) -> {
                        if (ex == null) return;
                        Throwable t = unwrap(ex);
                        if (t instanceof TimeoutException) {
                            log.warn("TIMEOUT ensure guest email={} timeout={}s", email, TimeUnit.MINUTES.toSeconds(10));
                            return;
                        }
                        if (t instanceof ApiException ae) {
                            Integer sc = ae.getResponseStatusCode();
                            log.error("FAILED ensure guest email={} status={} msg={}", email, sc, ae.getMessage(), ae);
                            return;
                        }
                        log.error("FAILED ensure guest email={} exType={} msg={}", email, t.getClass().getName(), t.getMessage(), t);
                    });

            futures.add(f);
        }

        if (toCreate > 0) {
            log.info("Guest phase: cached={} created={} total={}", cachedGuests, toCreate, emails.size());
        } else {
            log.debug("Guest phase: cached={} created=0 total={}", cachedGuests, emails.size());
        }


        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(30, TimeUnit.MINUTES)
                    .join();
        } catch (Exception e) {
            Throwable t = unwrap(e);
            log.warn("Guest phase completed WITH ERRORS. cause={}", t.toString(), t);
        }

        return userIdByEmail;
    }

    private void runMembershipsAsync(
            Map<String, Set<String>> desiredGroupsByEmail,
            Map<String, String> userIdByEmail,
            Map<String, String> groupIdByName,
            AtomicInteger added,
            AtomicInteger skipped,
            AtomicInteger failed
    ) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Map.Entry<String, Set<String>> entry : desiredGroupsByEmail.entrySet()) {
            String email = entry.getKey();
            String userId = userIdByEmail.get(email);

            if (userId == null || userId.isBlank()) {
                failed.incrementAndGet();
                log.warn("Skipping membership for email={} because userId not resolved", email);
                continue;
            }

            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                        for (String groupName : entry.getValue()) {
                            String groupId = groupIdByName.get(groupName);
                            if (groupId == null || groupId.isBlank()) {
                                failed.incrementAndGet();
                                continue;
                            }

                            Set<String> members = entraCache.getGroupMembers(groupId);
                            if (members != null && members.contains(userId)) {
                                skipped.incrementAndGet();
                                continue;
                            }

                            acquire(addLimit);
                            try {
                                withRetry("ensureMembership", "userId=" + userId + " group=" + groupName, () -> {
                                    graphGroupService.ensureUserInGroupsAsync(userId, List.of(groupName)).join();
                                    return null;
                                });

                                entraCache.addMemberToGroup(groupId, userId);
                                added.incrementAndGet();

                            } catch (RuntimeException ex) {
                                failed.incrementAndGet();
                                throw ex;
                            } finally {
                                addLimit.release();
                            }
                        }
                    }, executor)
                    .orTimeout(10, TimeUnit.MINUTES)
                    .whenComplete((_, ex) -> {
                        if (ex == null) return;

                        Throwable t = unwrap(ex);
                        if (t instanceof TimeoutException) {
                            log.warn("TIMEOUT membership userId={} email={} timeout={}s",
                                    userId, email, TimeUnit.MINUTES.toSeconds(10));
                            return;
                        }
                        if (t instanceof ApiException ae) {
                            Integer sc = ae.getResponseStatusCode();
                            log.error("FAILED membership userId={} email={} status={} msg={}",
                                    userId, email, sc, ae.getMessage(), ae);
                            return;
                        }
                        log.error("FAILED membership userId={} email={} exType={} msg={}",
                                userId, email, t.getClass().getName(), t.getMessage(), t);
                    });

            futures.add(f);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(40, TimeUnit.MINUTES)
                    .whenComplete((_, ex) -> {
                        if (ex != null) {
                            Throwable t = unwrap(ex);
                            log.warn("Membership phase completed WITH ERRORS. users={} cause={}",
                                    desiredGroupsByEmail.size(), t.toString());
                        } else {
                            log.debug("Membership phase completed OK. users={}", desiredGroupsByEmail.size());
                        }
                        log.debug("Membership counters added={} skipped={} failed={}", added.get(), skipped.get(), failed.get());
                    })
                    .join();
        } catch (Exception e) {
            Throwable t = unwrap(e);
            log.warn("Membership phase finished WITH ERRORS. cause={}", t.toString(), t);
        }
    }

    private Map<String, Set<String>> buildDesiredMembersByGroupId(
            Map<String, Set<String>> desiredGroupsByEmail,
            Map<String, String> userIdByEmail,
            Map<String, String> groupIdByName
    ) {
        Map<String, Set<String>> desiredMembersByGroupId = new HashMap<>();

        for (Map.Entry<String, Set<String>> entry : desiredGroupsByEmail.entrySet()) {
            String email = entry.getKey();
            String userId = userIdByEmail.get(email);
            if (userId == null || userId.isBlank()) continue;

            for (String groupName : entry.getValue()) {
                String groupId = groupIdByName.get(groupName);
                if (groupId != null && !groupId.isBlank()) {
                    desiredMembersByGroupId.computeIfAbsent(groupId, _ -> new HashSet<>()).add(userId);
                }
            }
        }

        return desiredMembersByGroupId;
    }

    private void reconcileGroups(Set<String> groupNamesToReconcile,
                                 Map<String, Set<String>> desiredMembersByGroupId,
                                 Map<String, String> groupIdByNameFromCache) {

        if (groupNamesToReconcile == null || groupNamesToReconcile.isEmpty()) {
            log.warn("No groups to reconcile.");
            return;
        }

        Map<String, String> groupIdByName = new HashMap<>();
        for (String groupName : groupNamesToReconcile) {
            String groupId = groupIdByNameFromCache.get(groupName);
            if (groupId != null) groupIdByName.put(groupName, groupId);
        }

        if (groupIdByName.isEmpty()) {
            log.warn("No groupIds resolved from cache, reconcile skipped.");
            return;
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Map.Entry<String, String> entry : groupIdByName.entrySet()) {
            String groupName = entry.getKey();
            String groupId = entry.getValue();

            Set<String> desired = desiredMembersByGroupId.getOrDefault(groupId, Set.of());
            Set<String> current = Optional.ofNullable(entraCache.getGroupMembers(groupId))
                    .map(HashSet::new)
                    .orElseGet(HashSet::new);

            Set<String> toRemove = new HashSet<>(current);
            toRemove.removeAll(desired);

            log.debug("Reconcile group '{}' ({}) desired={} current={} remove={}",
                    groupName, groupId, desired.size(), current.size(), toRemove.size());

            for (String userId : toRemove) {
                CompletableFuture<Void> f = CompletableFuture
                        .runAsync(() -> {
                            withRetry("removeMembership", "userId=" + userId + " groupId=" + groupId, () -> {
                                graphGroupService.removeUserFromGroupAsync(userId, groupId).join();
                                return null;
                            });
                            entraCache.removeMemberFromGroup(groupId, userId);
                        }, executor)
                        .orTimeout(10, TimeUnit.MINUTES)
                        .whenComplete((_, ex) -> {
                            if (ex != null) {
                                Throwable t = unwrap(ex);
                                log.error("FAILED to remove userId={} from group '{}' ({}). ErrorMessage={}",
                                        userId, groupName, groupId, t.getMessage());
                            } else {
                                log.info("Removed userId={} from groupName={} - groupId={}",
                                        userId, groupName, groupId);
                            }
                        });

                futures.add(f);
            }
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((_, ex) -> {
                    if (ex != null) {
                        Throwable t = unwrap(ex);
                        log.warn("Reconcile finished WITH ERRORS. Ops={} cause={}", futures.size(), t.toString());
                    } else {
                        if (!futures.isEmpty()) {
                            log.info("Reconcile finished. Removed {} members", futures.size());
                        } else {
                            log.debug("Reconcile finished. No members removed");
                        }

                    }
                })
                .join();
    }

    private <T> T withRetry(String op, String key, Callable<T> fn) {
        final int maxAttempts = 7;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return fn.call();
            } catch (Exception ex) {
                Throwable t = unwrap(ex);

                Integer sc = (t instanceof ApiException ae) ? ae.getResponseStatusCode() : null;
                boolean retryable = isRetryable(t, sc);

                if (!retryable || attempt == maxAttempts) {
                    if (t instanceof ApiException ae) {
                        log.error("{} FAILED key={} status={} attempt={}/{} msg={}",
                                op, key, sc, attempt, maxAttempts, ae.getMessage());
                    } else {
                        log.error("{} FAILED key={} attempt={}/{} exType={} msg={}",
                                op, key, attempt, maxAttempts, t.getClass().getName(), t.getMessage());
                    }
                    throw (ex instanceof RuntimeException) ? (RuntimeException) ex : new RuntimeException(ex);
                }

                long sleep = backoffMs(attempt);
                if (sc != null) {
                    log.warn("{} RETRY key={} status={} attempt={}/{} sleepMs={}",
                            op, key, sc, attempt, maxAttempts, sleep);
                } else {
                    log.warn("{} RETRY key={} attempt={}/{} sleepMs={} ex={}",
                            op, key, attempt, maxAttempts, sleep, t.toString());
                }
                sleepQuietly(sleep);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private boolean isRetryable(Throwable t, Integer statusCode) {
        if (t instanceof TimeoutException) return true;

        if (statusCode != null) {
            if (statusCode == 429) return true;
            if (statusCode == 503 || statusCode == 504) return true;
            return statusCode >= 500 && statusCode <= 599;
        }

        return t instanceof IOException;
    }

    private long backoffMs(int attempt) {
        long exp = 500L * (1L << Math.min(6, attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(0, 350);
        return Math.min(30_000, exp + jitter);
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static Throwable unwrap(Throwable ex) {
        Throwable t = ex;
        while (t instanceof CompletionException || t instanceof ExecutionException) {
            if (t.getCause() == null) break;
            t = t.getCause();
        }
        return t;
    }

    private static void acquire(Semaphore s) {
        try {
            s.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for permit", e);
        }
    }

    private Set<String> getManagedGroupNamesFromConfig() {
        try {
            List<String> list = graphProperties.getGroupMappings();
            if (list == null) return Set.of();
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toSet());
        } catch (NoSuchMethodError | Exception e) {
            return Set.of();
        }
    }

    private String normalizeEmail(String email) {
        if (email == null) return null;
        String e = email.trim().toLowerCase();
        return e.isBlank() ? null : e;
    }

    private boolean isFederatedUser(QlikUserDto user) {
        return user.getAssignedGroups() != null &&
                user.getAssignedGroups().stream()
                        .anyMatch(g -> "idp".equalsIgnoreCase(g.getProviderType()));
    }

    private boolean isExcludedDomain(String email) {
        List<String> excluded = qlikProperties.getExcludedEmailDomains();
        if (excluded == null || excluded.isEmpty()) return false;

        int atIdx = email.lastIndexOf('@');
        if (atIdx < 0 || atIdx == email.length() - 1) return false;

        String domain = email.substring(atIdx + 1).toLowerCase();

        return excluded.stream()
                .filter(d -> d != null && !d.isBlank())
                .map(String::toLowerCase)
                .anyMatch(domain::equals);
    }

    private record Desired(Map<String, Set<String>> desiredGroupsByEmail, Map<String, String> displayNameByEmail,
                           Set<String> groupsToUse) {
    }
}

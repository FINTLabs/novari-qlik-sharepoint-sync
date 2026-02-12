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
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserSyncService {

    private static final int THREADS = 4;

    private static final int INVITE_CONCURRENCY = 16;
    private static final int ADD_MEMBERSHIP_CONCURRENCY = 16;

    private static final long ENSURE_GUEST_TIMEOUT_MIN = 10;
    private static final long ENSURE_GUEST_PHASE_TIMEOUT_MIN = 30;

    private static final long MEMBERSHIP_TIMEOUT_MIN = 10;
    private static final long MEMBERSHIP_PHASE_TIMEOUT_MIN = 40;

    private static final long RECONCILE_TIMEOUT_MIN = 10;

    private final QlikUserClient qlikUserClient;
    private final GraphUserService graphUserService;
    private final GraphGroupService graphGroupService;
    private final QlikToAadGroupMappingService mappingService;
    private final QlikProperties qlikProperties;
    private final GraphProperties graphProperties;
    private final EntraCache entraCache;

    private final ExecutorService executor = Executors.newFixedThreadPool(THREADS);
    private final Semaphore inviteLimit = new Semaphore(INVITE_CONCURRENCY);
    private final Semaphore addLimit = new Semaphore(ADD_MEMBERSHIP_CONCURRENCY);

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
        syncRecent90();
    }

    public void syncRecent90() {
        syncInternal("RECENT_90", qlikUserClient::getAllUsersRecent90UsingCache400);
    }

    public void syncFull400() {
        syncInternal("FULL_400", qlikUserClient::refreshCacheFull400AndGetAllUsers);
    }

    private void syncInternal(String tag, Supplier<List<QlikUserDto>> qlikFetchFn) {
        long t0 = System.currentTimeMillis();

        int membersBefore = entraCache.totalMembersAcrossAllGroups();

        List<QlikUserDto> users = qlikFetchFn.get();
        if (users == null) {
            log.warn("Skipping sync/reconcile because Qlik fetch failed. Tag={}", tag);
            return;
        }

        Desired desired = buildDesired(users, getManagedGroupNamesFromConfig());

        int usersFound = desired.desiredGroupsByEmail.size();
        int groupsFound = desired.groupsToUse.size();

        if (usersFound == 0) {
            int membersAfter = entraCache.totalMembersAcrossAllGroups();
            logMembersTotals(tag, membersBefore, membersAfter);

            log.info("SyncDone Tag={} UsersFound=0 GroupsFound={} ElapsedTime={}",
                    tag, groupsFound, formatElapsed(System.currentTimeMillis() - t0));
            return;
        }

        Map<String, String> groupIdByName = resolveGroupIdsFromCache(desired.groupsToUse);
        Map<String, String> userIdByEmail = ensureGuests(desired.desiredGroupsByEmail.keySet(), desired.displayNameByEmail);

        AtomicInteger added = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        runMemberships(desired.desiredGroupsByEmail, userIdByEmail, groupIdByName, added, skipped, failed);

        ReconcileResult reconcile = maybeReconcile(tag, desired, userIdByEmail, groupIdByName);

        int membersAfter = entraCache.totalMembersAcrossAllGroups();
        logMembersTotals(tag, membersBefore, membersAfter);

        logCleanupSummaryIfEnabled(tag, reconcile);

        long elapsedMs = System.currentTimeMillis() - t0;
        log.info("SyncSummary Tag={} UsersFound={} GroupsFound={} Added={} Skipped={} Failed={} ElapsedTime={}",
                tag, usersFound, groupsFound, added.get(), skipped.get(), failed.get(), formatElapsed(elapsedMs));
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

            Set<String> effectiveGroups = filterToManagedIfConfigured(targetGroupNames, managedGroupNames);
            if (effectiveGroups.isEmpty()) continue;

            observedGroupNames.addAll(effectiveGroups);

            desiredGroupsByEmail.merge(email, effectiveGroups, (a, b) -> {
                a.addAll(b);
                return a;
            });

            String name = u.getName();
            if (name != null && !name.isBlank()) {
                displayNameByEmail.putIfAbsent(email, name);
            }
        }

        Set<String> groupsToUse = !managedGroupNames.isEmpty() ? managedGroupNames : observedGroupNames;
        return new Desired(desiredGroupsByEmail, displayNameByEmail, groupsToUse);
    }

    private Set<String> filterToManagedIfConfigured(Set<String> targetGroupNames, Set<String> managedGroupNames) {
        if (managedGroupNames == null || managedGroupNames.isEmpty()) {
            return new HashSet<>(targetGroupNames);
        }
        return targetGroupNames.stream()
                .filter(managedGroupNames::contains)
                .collect(Collectors.toSet());
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

    private Map<String, String> ensureGuests(Set<String> emails, Map<String, String> displayNameByEmail) {
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
            futures.add(ensureGuestAsync(email, displayNameByEmail, userIdByEmail));
        }

        logGuestPhase(cachedGuests, toCreate, emails.size());

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(ENSURE_GUEST_PHASE_TIMEOUT_MIN, TimeUnit.MINUTES)
                    .join();
        } catch (Exception e) {
            Throwable t = unwrap(e);
            log.warn("GuestPhaseCompletedWithErrors Cause={}", t.toString(), t);
        }

        return userIdByEmail;
    }

    private CompletableFuture<Void> ensureGuestAsync(
            String email,
            Map<String, String> displayNameByEmail,
            Map<String, String> userIdByEmail
    ) {
        return CompletableFuture
                .supplyAsync(() -> {
                    acquire(inviteLimit);
                    try {
                        String displayName = displayNameByEmail.getOrDefault(email, email);
                        return withRetry("ensureGuest", email,
                                () -> graphUserService.ensureGuestUserId(email, displayName));
                    } finally {
                        inviteLimit.release();
                    }
                }, executor)
                .orTimeout(ENSURE_GUEST_TIMEOUT_MIN, TimeUnit.MINUTES)
                .thenAccept(userId -> {
                    userIdByEmail.put(email, userId);
                    entraCache.putGuest(email, userId);
                })
                .whenComplete((_, ex) -> logEnsureGuestFailureIfAny(email, ex));
    }

    private void logEnsureGuestFailureIfAny(String email, Throwable ex) {
        if (ex == null) return;

        Throwable t = unwrap(ex);
        if (t instanceof TimeoutException) {
            log.warn("TIMEOUT ensure guest email={} timeout={}s", email, TimeUnit.MINUTES.toSeconds(ENSURE_GUEST_TIMEOUT_MIN));
            return;
        }
        if (t instanceof ApiException ae) {
            Integer sc = ae.getResponseStatusCode();
            log.error("FAILED ensure guest email={} status={} msg={}", email, sc, ae.getMessage(), ae);
            return;
        }
        log.error("FAILED ensure guest email={} exType={} msg={}", email, t.getClass().getName(), t.getMessage(), t);
    }

    private void logGuestPhase(int cachedGuests, int toCreate, int total) {
        if (toCreate > 0) {
            log.info("GuestPhase Cached={} Created={} Total={}", cachedGuests, toCreate, total);
        } else {
            log.debug("GuestPhase Cached={} Created=0 Total={}", cachedGuests, total);
        }
    }

    private void runMemberships(
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

            futures.add(runUserMembershipsAsync(email, userId, entry.getValue(), groupIdByName, added, skipped, failed));
        }

        awaitMembershipPhase(desiredGroupsByEmail.size(), futures, added, skipped, failed);
    }

    private CompletableFuture<Void> runUserMembershipsAsync(
            String email,
            String userId,
            Set<String> groupNames,
            Map<String, String> groupIdByName,
            AtomicInteger added,
            AtomicInteger skipped,
            AtomicInteger failed
    ) {
        return CompletableFuture
                .runAsync(() -> {
                    for (String groupName : groupNames) {
                        String groupId = groupIdByName.get(groupName);
                        if (groupId == null || groupId.isBlank()) {
                            failed.incrementAndGet();
                            continue;
                        }

                        if (isAlreadyMember(groupId, userId)) {
                            skipped.incrementAndGet();
                            continue;
                        }

                        addUserToGroupWithLimit(userId, groupName, groupId, added, failed);
                    }
                }, executor)
                .orTimeout(MEMBERSHIP_TIMEOUT_MIN, TimeUnit.MINUTES)
                .whenComplete((_, ex) -> logMembershipFailureIfAny(userId, email, ex));
    }

    private boolean isAlreadyMember(String groupId, String userId) {
        Set<String> members = entraCache.getGroupMembers(groupId);
        return members != null && members.contains(userId);
    }

    private void addUserToGroupWithLimit(
            String userId,
            String groupName,
            String groupId,
            AtomicInteger added,
            AtomicInteger failed
    ) {
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

    private void logMembershipFailureIfAny(String userId, String email, Throwable ex) {
        if (ex == null) return;

        Throwable t = unwrap(ex);
        if (t instanceof TimeoutException) {
            log.warn("TIMEOUT membership userId={} email={} timeout={}s",
                    userId, email, TimeUnit.MINUTES.toSeconds(MEMBERSHIP_TIMEOUT_MIN));
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
    }

    private void awaitMembershipPhase(
            int users,
            List<CompletableFuture<Void>> futures,
            AtomicInteger added,
            AtomicInteger skipped,
            AtomicInteger failed
    ) {
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(MEMBERSHIP_PHASE_TIMEOUT_MIN, TimeUnit.MINUTES)
                    .whenComplete((_, ex) -> {
                        if (ex != null) {
                            Throwable t = unwrap(ex);
                            log.warn("MembershipPhaseCompletedWithErrors Users={} Cause={}", users, t.toString());
                        } else {
                            log.debug("MembershipPhaseCompletedOk Users={}", users);
                        }
                        log.debug("MembershipCounters Added={} Skipped={} Failed={}", added.get(), skipped.get(), failed.get());
                    })
                    .join();
        } catch (Exception e) {
            Throwable t = unwrap(e);
            log.warn("MembershipPhaseFinishedWithErrors Cause={}", t.toString(), t);
        }
    }

    private ReconcileResult maybeReconcile(
            String tag,
            Desired desired,
            Map<String, String> userIdByEmail,
            Map<String, String> groupIdByName
    ) {
        if (!qlikProperties.isCleanupRemoveMemberships()) {
            log.warn("Cleanup/reconcile disabled by config. Tag={}", tag);
            return ReconcileResult.empty();
        }

        Map<String, Set<String>> desiredMembersByGroupId =
                buildDesiredMembersByGroupId(desired.desiredGroupsByEmail, userIdByEmail, groupIdByName);

        return reconcileGroups(desired.groupsToUse, desiredMembersByGroupId, groupIdByName);
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

    private ReconcileResult reconcileGroups(
            Set<String> groupNamesToReconcile,
            Map<String, Set<String>> desiredMembersByGroupId,
            Map<String, String> groupIdByNameFromCache
    ) {
        if (groupNamesToReconcile == null || groupNamesToReconcile.isEmpty()) {
            log.warn("No groups to reconcile.");
            return ReconcileResult.empty();
        }

        Map<String, String> groupIdByName = resolveGroupIdsForReconcile(groupNamesToReconcile, groupIdByNameFromCache);
        if (groupIdByName.isEmpty()) {
            log.warn("No groupIds resolved from cache, reconcile skipped.");
            return ReconcileResult.empty();
        }

        AtomicInteger membershipsToRemove = new AtomicInteger();
        AtomicInteger membershipsRemoved = new AtomicInteger();
        AtomicInteger failedRemoves = new AtomicInteger();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Map.Entry<String, String> entry : groupIdByName.entrySet()) {
            String groupName = entry.getKey();
            String groupId = entry.getValue();

            Set<String> desired = desiredMembersByGroupId.getOrDefault(groupId, Set.of());
            Set<String> current = copyMembers(groupId);

            Set<String> toRemove = new HashSet<>(current);
            toRemove.removeAll(desired);

            membershipsToRemove.addAndGet(toRemove.size());

            if (!toRemove.isEmpty()) {
                log.info("ReconcileGroup GroupName={} GroupId={} DesiredMembers={} CurrentMembers={} RemoveMembers={}",
                        groupName, groupId, desired.size(), current.size(), toRemove.size());
            }

            for (String userId : toRemove) {
                futures.add(removeMembershipAsync(userId, groupName, groupId, membershipsRemoved, failedRemoves));
            }
        }

        awaitReconcilePhase(futures, membershipsRemoved, failedRemoves);

        return new ReconcileResult(groupIdByName.size(),
                membershipsToRemove.get(), membershipsRemoved.get(), failedRemoves.get());
    }

    private Map<String, String> resolveGroupIdsForReconcile(Set<String> groupNamesToReconcile,
                                                            Map<String, String> groupIdByNameFromCache) {
        Map<String, String> groupIdByName = new HashMap<>();
        for (String groupName : groupNamesToReconcile) {
            String groupId = groupIdByNameFromCache.get(groupName);
            if (groupId != null && !groupId.isBlank()) {
                groupIdByName.put(groupName, groupId);
            }
        }
        return groupIdByName;
    }

    private Set<String> copyMembers(String groupId) {
        return Optional.ofNullable(entraCache.getGroupMembers(groupId))
                .map(HashSet::new)
                .orElseGet(HashSet::new);
    }

    private CompletableFuture<Void> removeMembershipAsync(
            String userId,
            String groupName,
            String groupId,
            AtomicInteger membershipsRemoved,
            AtomicInteger failedRemoves
    ) {
        return CompletableFuture
                .runAsync(() -> {
                    withRetry("removeMembership", "userId=" + userId + " groupId=" + groupId, () -> {
                        graphGroupService.removeUserFromGroupAsync(userId, groupId).join();
                        return null;
                    });
                    entraCache.removeMemberFromGroup(groupId, userId);
                    membershipsRemoved.incrementAndGet();
                }, executor)
                .orTimeout(RECONCILE_TIMEOUT_MIN, TimeUnit.MINUTES)
                .whenComplete((_, ex) -> {
                    if (ex == null) return;
                    failedRemoves.incrementAndGet();
                    Throwable t = unwrap(ex);
                    log.error("RemoveMembershipFailed UserId={} GroupName={} GroupId={} ErrorMessage={}",
                            userId, groupName, groupId, t.getMessage());
                });
    }

    private void awaitReconcilePhase(List<CompletableFuture<Void>> futures,
                                     AtomicInteger membershipsRemoved,
                                     AtomicInteger failedRemoves) {

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((_, ex) -> {
                    if (ex != null) {
                        Throwable t = unwrap(ex);
                        log.warn("ReconcileFinishedWithErrors Ops={} Cause={}", futures.size(), t.toString());
                    } else {
                        log.info("ReconcileFinished Ops={} MembershipsRemoved={} FailedRemoves={}",
                                futures.size(), membershipsRemoved.get(), failedRemoves.get());
                    }
                })
                .join();
    }

    private void logCleanupSummaryIfEnabled(String tag, ReconcileResult reconcileResult) {
        if (!qlikProperties.isCleanupRemoveMemberships()) return;

        log.info("MembershipCleanupSummary Tag={} GroupsReconciled={} MembershipsToRemove={} MembershipsRemoved={} FailedRemoves={}",
                tag,
                reconcileResult.groupsReconciled,
                reconcileResult.membershipsToRemove,
                reconcileResult.membershipsRemoved,
                reconcileResult.failedRemoves);
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
                    logFinalFailure(op, key, attempt, maxAttempts, t, sc);
                    throw (ex instanceof RuntimeException) ? (RuntimeException) ex : new RuntimeException(ex);
                }

                long sleep = backoffMs(attempt);
                logRetry(op, key, attempt, maxAttempts, t, sc, sleep);
                sleepQuietly(sleep);
            }
        }

        throw new IllegalStateException("unreachable");
    }

    private void logFinalFailure(String op, String key, int attempt, int maxAttempts, Throwable t, Integer sc) {
        if (t instanceof ApiException ae) {
            log.error("{} FAILED key={} status={} attempt={}/{} msg={}",
                    op, key, sc, attempt, maxAttempts, ae.getMessage());
        } else {
            log.error("{} FAILED key={} attempt={}/{} exType={} msg={}",
                    op, key, attempt, maxAttempts, t.getClass().getName(), t.getMessage());
        }
    }

    private void logRetry(String op, String key, int attempt, int maxAttempts, Throwable t, Integer sc, long sleep) {
        if (sc != null) {
            log.warn("{} RETRY key={} status={} attempt={}/{} sleepMs={}",
                    op, key, sc, attempt, maxAttempts, sleep);
        } else {
            log.warn("{} RETRY key={} attempt={}/{} sleepMs={} ex={}",
                    op, key, attempt, maxAttempts, sleep, t.toString());
        }
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

    private void logMembersTotals(String tag, int beforeTotal, int afterTotal) {
        log.info("SyncGroupMembersTotals Tag={} GroupMembersBeforeTotal={} GroupMembersAfterTotal={}",
                tag, beforeTotal, afterTotal);
    }

    private String formatElapsed(long ms) {
        long minutes = TimeUnit.MILLISECONDS.toMinutes(ms);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60;
        return minutes + "m " + seconds + "s";
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

    private record Desired(
            Map<String, Set<String>> desiredGroupsByEmail,
            Map<String, String> displayNameByEmail,
            Set<String> groupsToUse
    ) {}

    private static final class ReconcileResult {
        final int groupsReconciled;
        final int membershipsToRemove;
        final int membershipsRemoved;
        final int failedRemoves;

        ReconcileResult(int groupsReconciled, int membershipsToRemove, int membershipsRemoved, int failedRemoves) {
            this.groupsReconciled = groupsReconciled;
            this.membershipsToRemove = membershipsToRemove;
            this.membershipsRemoved = membershipsRemoved;
            this.failedRemoves = failedRemoves;
        }

        static ReconcileResult empty() {
            return new ReconcileResult(0, 0, 0, 0);
        }
    }
}

package no.novari.qliktosharepoint.cache;

import com.microsoft.graph.models.DirectoryObject;
import com.microsoft.graph.models.User;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.kiota.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import no.novari.qliktosharepoint.config.GraphProperties;
import no.novari.qliktosharepoint.service.GraphGroupService;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class EntraCacheRefresher {

    private final GraphServiceClient graph;
    private final GraphProperties graphProperties;
    private final EntraCache cache;

    public void refreshCache() {
        log.debug("Refreshing cache of Entra objects (guests + groups with memberships) ...");

        Map<String, String> groupIdByName = new HashMap<>();
        Map<String, Set<String>> membersByGroupId = new HashMap<>();
        Map<String, String> guestIdByEmail = new HashMap<>();

        try {
            refreshAllGuestsInto(guestIdByEmail);

            List<String> groupNames = normalizedGroupNames(graphProperties.getGroupMappings());
            if (groupNames.isEmpty()) {
                log.warn("No group-mappings configured. Group membership refresh skipped.");
            } else {
                for (String groupName : groupNames) {
                    resolveGroupIdByDisplayName(groupName).ifPresent(groupId ->
                            groupIdByName.put(groupName, groupId)
                    );
                }


                for (Map.Entry<String, String> e : groupIdByName.entrySet()) {
                    String groupName = e.getKey();
                    String groupId = e.getValue();

                    try {
                        Set<String> members = fetchGroupMembers(groupId);
                        membersByGroupId.put(groupId, members);
                        log.debug("Fetched members for groupName={} groupId={} members={}",
                                groupName, groupId, members.size());
                    } catch (Exception ex) {
                        log.error("Failed refreshing members for groupName='{}' groupId={}", groupName, groupId, ex);
                    }
                }
            }

            cache.clearAll();

            guestIdByEmail.forEach(cache::putGuest);
            membersByGroupId.forEach(cache::setGroupMembers);
            groupIdByName.forEach(cache::putGroupIdByDisplayName);

            cache.markRefreshed();
            log.debug("Entra cache refresh finished. lastRefresh={}", cache.getLastRefresh()
                    .truncatedTo(ChronoUnit.SECONDS)
                    .atZone(ZoneId.systemDefault()));

        } catch (Exception e) {
            log.error("Entra cache refresh FAILED. Keeping existing cache. Cause={}", e.getMessage(), e);
        }
        log.debug(
                "Entra cache refreshed guests={} groups={} lastRefresh={}",
                guestIdByEmail.size(),
                membersByGroupId.size(),
                cache.getLastRefresh()
                        .truncatedTo(ChronoUnit.SECONDS)
                        .atZone(ZoneId.systemDefault())
        );
    }

    private Optional<String> resolveGroupIdByDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) return Optional.empty();

        String escaped = displayName.replace("'", "''");
        String filter = "displayName eq '" + escaped + "'";

        try {
            var page = graph.groups().get(req -> {
                req.queryParameters.filter = filter;
                req.queryParameters.top = 1;
                req.queryParameters.select = new String[]{"id", "displayName"};
            });

            if (page == null || page.getValue() == null || page.getValue().isEmpty()) {
                return Optional.empty();
            }

            String id = page.getValue().getFirst().getId();
            return (id == null || id.isBlank()) ? Optional.empty() : Optional.of(id);

        } catch (ApiException e) {
            log.error("Failed resolving groupId for displayName='{}' msg={}", displayName, e.getMessage(), e);
            return Optional.empty();
        }
    }


    private static List<String> normalizedGroupNames(List<String> groupNames) {
        if (groupNames == null) return List.of();
        return groupNames.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    private Set<String> fetchGroupMembers(String groupId) {
        String next = null;
        Set<String> memberIds = new HashSet<>();

        do {
            var page = (next == null)
                    ? graph.groups().byGroupId(groupId).members().get(req -> req.queryParameters.top = 999)
                    : graph.groups().byGroupId(groupId).members().withUrl(next).get();

            if (page == null || page.getValue() == null) break;

            for (DirectoryObject obj : page.getValue()) {
                if (obj != null && obj.getId() != null && !obj.getId().isBlank()) {
                    memberIds.add(obj.getId());
                }
            }

            next = page.getOdataNextLink();
        } while (next != null);

        return memberIds;
    }

    private String pickEmail(User u) {
        String email = u.getMail();
        if (email == null || email.isBlank()) email = u.getUserPrincipalName();
        if (email == null || email.isBlank()) return null;
        return email.toLowerCase();
    }

    private void refreshAllGuestsInto(Map<String, String> guestIdByEmail) {
        log.debug("Refreshing ALL guest users into cache...");
        long count = 0;
        String next = null;

        do {
            var page = (next == null)
                    ? graph.users().get(req -> {
                req.queryParameters.filter = "userType eq 'Guest'";
                req.queryParameters.select = new String[]{"id", "mail", "userPrincipalName", "userType"};
                req.queryParameters.top = 999;
            })
                    : graph.users().withUrl(next).get();

            if (page == null || page.getValue() == null) break;

            for (User u : page.getValue()) {
                if (!"Guest".equalsIgnoreCase(u.getUserType())) continue;

                String email = pickEmail(u);
                if (email != null && u.getId() != null && !u.getId().isBlank()) {
                    guestIdByEmail.put(email, u.getId());
                    count++;
                }
            }

            next = page.getOdataNextLink();
        } while (next != null);

        log.debug("Fetched {} guest users", count);

    }
}

package no.novari.qliktosharepoint.service;

import no.novari.qliktosharepoint.cache.EntraCache;
import no.novari.qliktosharepoint.config.GraphProperties;
import no.novari.qliktosharepoint.config.QlikProperties;
import no.novari.qliktosharepoint.qlik.AssignedGroupDto;
import no.novari.qliktosharepoint.qlik.QlikUserClient;
import no.novari.qliktosharepoint.qlik.QlikUserDto;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserSyncServiceTest {

    @Test
    void runSyncOnce_createsXUsers_whereYPercentFilteredOut_byFederatedRule() {
        int totalUsers = 2836;
        int filteredPercent = 15;

        IntPredicate isIncluded = i -> (i * 100 / totalUsers) >= filteredPercent;
        int expectedIncluded = 0;

        QlikUserClient qlikUserClient = mock(QlikUserClient.class);
        GraphUserService graphUserService = mock(GraphUserService.class);
        GraphGroupService graphGroupService = mock(GraphGroupService.class);
        QlikToAadGroupMappingService mappingService = mock(QlikToAadGroupMappingService.class);
        QlikProperties qlikProperties = mock(QlikProperties.class);
        GraphProperties graphProperties = mock(GraphProperties.class);
        EntraCache entraCache = mock(EntraCache.class);

        when(graphProperties.getGroupMappings()).thenReturn(List.of("GroupA"));

        when(qlikProperties.isCleanupRemoveMemberships()).thenReturn(false);
        when(qlikProperties.getExcludedEmailDomains()).thenReturn(List.of());

        when(entraCache.getGroupIdByDisplayName("GroupA")).thenReturn("groupA-id");

        when(entraCache.getGroupMembers("groupA-id")).thenReturn(Set.of());

        when(entraCache.getGuestIdByEmail(anyString())).thenReturn(null);

        List<QlikUserDto> users = new ArrayList<>();
        for (int i = 1; i <= totalUsers; i++) {
            boolean included = isIncluded.test(i);
            if (included) expectedIncluded++;

            QlikUserDto u = new QlikUserDto();
            u.setId("q" + i);
            u.setName("User " + i);
            u.setEmail("user" + i + "@example.com");
            u.setAssignedGroups(included
                    ? List.of(assignedGroup("idp"))
                    : List.of(assignedGroup("local")));
            users.add(u);
        }
        when(qlikUserClient.getAllUsersRecent90UsingCache400()).thenReturn(users);

        when(mappingService.resolveTargetAadGroupNames(any(QlikUserDto.class)))
                .thenReturn(Set.of("GroupA"));

        when(graphUserService.ensureGuestUserId(anyString(), anyString()))
                .thenAnswer(inv -> "entra-" + inv.getArgument(0));

        when(graphGroupService.ensureUserInGroupsAsync(anyString(), anyCollection()))
                .thenReturn(CompletableFuture.completedFuture(null));

        UserSyncService svc = new UserSyncService(
                qlikUserClient,
                graphUserService,
                graphGroupService,
                mappingService,
                qlikProperties,
                graphProperties,
                entraCache
        );

        svc.syncAll();

        verify(graphUserService, times(expectedIncluded))
                .ensureGuestUserId(anyString(), anyString());

        verify(graphGroupService, times(expectedIncluded))
                .ensureUserInGroupsAsync(anyString(), anyCollection());

        assertThat(expectedIncluded).isLessThan(totalUsers);
        assertThat(expectedIncluded).isGreaterThan(0);
    }

    private static AssignedGroupDto assignedGroup(String providerType) {
        AssignedGroupDto g = new AssignedGroupDto();
        g.setProviderType(providerType);
        return g;
    }
}

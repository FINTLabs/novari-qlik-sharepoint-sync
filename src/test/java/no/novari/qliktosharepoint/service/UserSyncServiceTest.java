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

        // NB: UserSyncService bruker graphProperties.getGroupMappings() som "managedGroupNames"
        when(graphProperties.getGroupMappings()).thenReturn(List.of("GroupA"));

        // Ikke kjør reconcile i testen
        when(qlikProperties.isCleanupRemoveMemberships()).thenReturn(false);
        when(qlikProperties.getExcludedEmailDomains()).thenReturn(List.of());

        // groupId kommer fra cache i ny kode
        when(entraCache.getGroupIdByDisplayName("GroupA")).thenReturn("groupA-id");

        // ingen medlemmer i utgangspunktet
        when(entraCache.getGroupMembers("groupA-id")).thenReturn(Set.of());

        // ingen guests cached → må "opprettes"
        when(entraCache.getGuestIdByEmail(anyString())).thenReturn(null);

        // Qlik users
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
        when(qlikUserClient.getAllUsers()).thenReturn(users);

        // mappingService må gi groupName som finnes i groupIdByName
        when(mappingService.resolveTargetAadGroupNames(any(QlikUserDto.class)))
                .thenReturn(Set.of("GroupA"));

        // user creation
        when(graphUserService.ensureGuestUserId(anyString(), anyString()))
                .thenAnswer(inv -> "entra-" + inv.getArgument(0));

        // membership add (kalles inne i en runAsync, men join() brukes)
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

        // expectedIncluded = antall federated (idp)
        verify(graphUserService, times(expectedIncluded))
                .ensureGuestUserId(anyString(), anyString());

        // Membership kalles for alle included (én gruppe per user i denne testen)
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

package no.novari.qliktosharepoint.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import no.novari.qliktosharepoint.config.GraphProperties;
import no.novari.qliktosharepoint.qlik.AssignedGroupDto;
import no.novari.qliktosharepoint.qlik.QlikUserDto;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class QlikToAadGroupMappingService {

    private final GraphProperties graphProperties;
    private final Map<String, String> prefixToAadGroupName = new HashMap<>();

    @PostConstruct
    public void init() {
        if (graphProperties.getGroupMappings() == null) {
            log.warn("No graph.group-mappings configured");
            return;
        }

        for (String aadGroupName : graphProperties.getGroupMappings()) {
            int idx = aadGroupName.indexOf('_');
            if (idx <= 0) {
                log.warn("Group mapping '{}' does not contain '_' – skipping", aadGroupName);
                continue;
            }
            String prefix = aadGroupName.substring(aadGroupName.startsWith("Qlik-") ? 5 : 0, idx + 1);
            prefixToAadGroupName.put(prefix, aadGroupName);
            log.debug("Configured mapping: prefix '{}' -> AAD group '{}'", prefix, aadGroupName);
        }
    }

    public Set<String> resolveTargetAadGroupNames(QlikUserDto user) {
        if (user.getAssignedGroups() == null || user.getAssignedGroups().isEmpty()) {
            return Collections.emptySet();
        }

        Set<String> result = new HashSet<>();

        for (AssignedGroupDto group : user.getAssignedGroups()) {
            String name = group.getName();
            if (name == null) {
                continue;
            }

            int idx = name.indexOf('_');
            if (idx <= 0) {
                continue;
            }

            String prefix = name.substring(0, idx + 1);
            String aadGroupName = prefixToAadGroupName.get(prefix);
            if (aadGroupName != null) {
                result.add(aadGroupName);
            }
        }

        if (!result.isEmpty()) {
            log.debug("User {} ({}) will be mapped to AAD groups: {}",
                    user.getName(), user.getEmail(), result);
        }

        return result;
    }
}

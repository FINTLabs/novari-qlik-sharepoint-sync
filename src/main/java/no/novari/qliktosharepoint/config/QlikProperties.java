package no.novari.qliktosharepoint.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
@Component
@ConfigurationProperties(prefix = "novari.qlik")
public class QlikProperties {
    private String baseUrl;
    private String apiToken;
    private String usersEndpoint;
    private String auditEndpoint;
    private Integer auditDaysBack;
    private List<String> excludedEmailDomains = new ArrayList<>();
    private boolean cleanupDeleteGuestUsers;
    private boolean cleanupRemoveMemberships;
}

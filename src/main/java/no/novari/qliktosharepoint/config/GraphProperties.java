package no.novari.qliktosharepoint.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "novari.graph")
public class GraphProperties {

    private String tenantId;
    private String clientId;
    private String clientSecret;
    private String inviteRedirectUrl;
    private String baseUrl;
    private List<String> groupMappings = new ArrayList<>();
}
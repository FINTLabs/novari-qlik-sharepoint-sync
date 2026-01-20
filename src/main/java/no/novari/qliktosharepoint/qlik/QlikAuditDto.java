package no.novari.qliktosharepoint.qlik;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class QlikAuditDto {
    private String id;
    private String contentType;
    private String eventId;
    private String eventTime;
    private String eventType;
    private String source;
    private String tenantId;
    private String userId;
    private String sessionId;
}


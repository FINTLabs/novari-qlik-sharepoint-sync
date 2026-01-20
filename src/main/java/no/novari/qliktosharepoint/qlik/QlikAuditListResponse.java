package no.novari.qliktosharepoint.qlik;

import lombok.Data;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class QlikAuditListResponse {
    private List<QlikAuditDto> data;
    private Links links;

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class Links {
        private Link self;
        private Link next;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class Link {
        private String href;
    }
}
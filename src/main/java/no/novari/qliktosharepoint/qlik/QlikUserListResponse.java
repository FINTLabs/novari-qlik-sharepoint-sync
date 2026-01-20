package no.novari.qliktosharepoint.qlik;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
public class QlikUserListResponse {
    private List<QlikUserDto> data;
    private Links links;

    @Getter
    @Setter
    public static class Links {
        private LinkHref self;
        private LinkHref next;
        @JsonIgnore
        private LinkHref prev;
    }

    @Getter
    @Setter
    public static class LinkHref {
        private String href;
    }

}

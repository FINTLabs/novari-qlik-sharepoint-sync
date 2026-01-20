package no.novari.qliktosharepoint.qlik;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
public class AssignedGroupDto {
    private String id;
    private String name;
    private String providerType;
}

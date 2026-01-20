package no.novari.qliktosharepoint.qlik;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class QlikUserDto {
    private String id;
    private String tenantId;
    private String status;
    private String subject;
    private String name;
    private String email;
    private List<AssignedGroupDto> assignedGroups;

}

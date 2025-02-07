package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Optional;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoleAssignmentAttributes {
    public static final String ATTRIBUTE_NOT_DEFINED = "Attribute not defined";
    private Optional<String> jurisdiction;
    private Optional<String> caseId;
    private Optional<String> caseType;
    private Optional<String> region;
    private Optional<String> location;
    private Optional<String> contractType;
    private Optional<String> caseAccessGroupId;
    private Optional<String> substantive;
}

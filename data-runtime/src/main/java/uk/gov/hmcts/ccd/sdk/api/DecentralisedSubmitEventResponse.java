package uk.gov.hmcts.ccd.sdk.api;

import java.util.List;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * Response type for submit event operations in the decentralised persistence API.
 * This is a copy of the type used by the ServicePersistenceAPI Feign client.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Data
public class DecentralisedSubmitEventResponse {
    private CaseDetails caseDetails;
    private List<String> errors;
    private List<String> warnings;
    private Boolean ignoreWarning;
}
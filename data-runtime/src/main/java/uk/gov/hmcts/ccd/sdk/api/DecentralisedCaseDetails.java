package uk.gov.hmcts.ccd.sdk.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * Represents case details in the decentralised persistence API.
 * This is a copy of the type used by the ServicePersistenceAPI Feign client.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Data
public class DecentralisedCaseDetails {
    private CaseDetails caseDetails;
}
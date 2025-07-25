package uk.gov.hmcts.ccd.sdk.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.Data;

/**
 * Represents a case event in the decentralised persistence API.
 * This is a copy of the type used by the ServicePersistenceAPI Feign client.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Data
@Builder
public class DecentralisedCaseEvent {

    private CaseDetails caseDetailsBefore;
    private CaseDetails caseDetails;
    private DecentralisedEventDetails eventDetails;

    @JsonCreator
    public DecentralisedCaseEvent(CaseDetails caseDetailsBefore, CaseDetails caseDetails,
                                  DecentralisedEventDetails eventDetails) {
        this.caseDetailsBefore = caseDetailsBefore;
        this.caseDetails = caseDetails;
        this.eventDetails = eventDetails;
    }
}
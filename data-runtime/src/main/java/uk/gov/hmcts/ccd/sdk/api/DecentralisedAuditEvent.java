package uk.gov.hmcts.ccd.sdk.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.Getter;
import uk.gov.hmcts.ccd.sdk.dto.AuditEvent;

/**
 * Represents an audit event in the decentralised persistence API.
 * This is a copy of the type used by the ServicePersistenceAPI Feign client.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Data
public class DecentralisedAuditEvent {
    private Long id;
    private Long caseReference;
    @Getter(lombok.AccessLevel.NONE)
    private AuditEvent event;

    public AuditEvent getEvent(String caseDataId) {
        this.event.setId(id);
        this.event.setCaseDataId(caseDataId);
        return event;
    }
}
package uk.gov.hmcts.ccd.sdk;

import java.util.UUID;
import lombok.NonNull;

/**
 * Locked case context supplied when a new system event action is executed.
 */
public record SystemEventExecutionContext(
    long caseReference,
    @NonNull UUID idempotencyKey,
    @NonNull String caseTypeId,
    @NonNull String currentState
) {
}

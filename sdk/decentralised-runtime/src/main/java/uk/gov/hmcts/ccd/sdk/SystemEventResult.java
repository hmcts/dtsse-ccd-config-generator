package uk.gov.hmcts.ccd.sdk;

import java.util.Optional;
import lombok.NonNull;

/**
 * History metadata and optional state transition produced by a system event action.
 */
public record SystemEventResult<State extends Enum<State>>(
    @NonNull String eventId,
    @NonNull String eventName,
    String summary,
    @NonNull Optional<State> state
) {}

package uk.gov.hmcts.ccd.sdk;

import lombok.NonNull;

/**
 * Trusted audit attribution for a person on whose behalf a system event is executed.
 */
public record ActorAttribution(
    @NonNull String id,
    @NonNull String firstName,
    @NonNull String lastName
) {}

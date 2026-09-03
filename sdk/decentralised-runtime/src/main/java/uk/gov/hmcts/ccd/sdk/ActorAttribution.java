package uk.gov.hmcts.ccd.sdk;

import java.util.Objects;

/**
 * Trusted audit attribution for a person on whose behalf a system event is executed.
 */
public record ActorAttribution(String id, String firstName, String lastName) {

  public ActorAttribution {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(firstName, "firstName");
    Objects.requireNonNull(lastName, "lastName");
  }
}

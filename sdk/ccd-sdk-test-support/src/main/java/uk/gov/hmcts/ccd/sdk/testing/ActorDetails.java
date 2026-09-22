package uk.gov.hmcts.ccd.sdk.testing;

import java.util.List;
import java.util.Objects;

/** Identity used by test submissions and audit records. */
public record ActorDetails(String uid, String email, String givenName, String familyName, List<String> roles) {

  public ActorDetails {
    Objects.requireNonNull(uid);
    Objects.requireNonNull(email);
    Objects.requireNonNull(givenName);
    Objects.requireNonNull(familyName);
    roles = List.copyOf(roles);
  }
}

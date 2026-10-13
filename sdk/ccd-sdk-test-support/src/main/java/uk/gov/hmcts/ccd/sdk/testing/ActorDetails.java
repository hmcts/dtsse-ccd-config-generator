package uk.gov.hmcts.ccd.sdk.testing;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Identity used by test submissions and audit records. */
public record ActorDetails(String uid, String email, String givenName, String familyName, List<String> roles) {

  public ActorDetails {
    Objects.requireNonNull(uid);
    Objects.requireNonNull(email);
    Objects.requireNonNull(givenName);
    Objects.requireNonNull(familyName);
    roles = List.copyOf(roles);
  }

  /**
   * An actor with a random UUID and an {@code example.com} address derived from the name.
   */
  public static ActorDetails of(String givenName, String familyName, String... roles) {
    return of(givenName, familyName, List.of(roles));
  }

  public static ActorDetails of(String givenName, String familyName, List<String> roles) {
    String email = (givenName + "." + familyName).toLowerCase(Locale.ROOT).replace(' ', '-') + "@example.com";
    return new ActorDetails(UUID.randomUUID().toString(), email, givenName, familyName, roles);
  }
}

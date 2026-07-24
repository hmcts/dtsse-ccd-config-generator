package uk.gov.hmcts.ccd.sdk.api.noc;

import java.util.List;
import java.util.Objects;

public record NocSubmitContext(
    String authorisation,
    String userId,
    String email,
    String name,
    String givenName,
    String familyName,
    List<String> roles
) {

  public NocSubmitContext {
    authorisation = Objects.requireNonNull(authorisation, "authorisation");
    userId = Objects.requireNonNull(userId, "userId");
    roles = roles == null ? List.of() : List.copyOf(roles);
  }
}

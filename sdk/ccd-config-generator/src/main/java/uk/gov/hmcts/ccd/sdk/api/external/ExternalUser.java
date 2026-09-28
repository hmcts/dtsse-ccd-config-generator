package uk.gov.hmcts.ccd.sdk.api.external;

import java.util.List;
import java.util.Objects;

/**
 * The user driving an external event, as IDAM knows them: their id, their roles, and the bearer
 * token they sent, as an Authorization header value, for the handler's calls to downstream
 * services such as CDAM.
 */
public record ExternalUser(String id, List<String> roles, String bearerToken) {

  public ExternalUser {
    Objects.requireNonNull(id);
    roles = roles == null ? List.of() : List.copyOf(roles);
  }

  /** Leaves the token out, so logging a request does not leak it. */
  @Override
  public String toString() {
    return "ExternalUser[id=" + id + ", roles=" + roles + "]";
  }
}

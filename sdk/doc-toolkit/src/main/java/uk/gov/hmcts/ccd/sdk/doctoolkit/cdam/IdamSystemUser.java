package uk.gov.hmcts.ccd.sdk.doctoolkit.cdam;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import uk.gov.hmcts.reform.idam.client.IdamClient;

/**
 * The service's system user, signed in to IDAM with its username and password. The token is reused
 * for half an hour, well inside IDAM's token lifetime, unless CDAM rejects it first.
 */
public final class IdamSystemUser implements Supplier<String> {
  private static final Duration REUSE = Duration.ofMinutes(30);

  private final IdamClient idam;
  private final String username;
  private final String password;
  private final Clock clock;
  private String token;
  private Instant signedInAt;

  public IdamSystemUser(IdamClient idam, String username, String password) {
    this(idam, username, password, Clock.systemUTC());
  }

  IdamSystemUser(IdamClient idam, String username, String password, Clock clock) {
    this.idam = idam;
    this.username = username;
    this.password = password;
    this.clock = clock;
  }

  /**
   * A bearer token for the user, as sent in the {@code Authorization} header.
   */
  @Override
  public synchronized String get() {
    Instant now = clock.instant();
    if (token == null || !now.isBefore(signedInAt.plus(REUSE))) {
      token = idam.getAccessToken(username, password);
      signedInAt = now;
    }
    return token;
  }

  /**
   * Forgets the token, so the next {@link #get} signs in again; for a token IDAM rejected.
   */
  public synchronized void signOut() {
    token = null;
  }
}

package uk.gov.hmcts.ccd.sdk.impl;

import static org.springframework.util.StringUtils.hasText;

import feign.FeignException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Supplier;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

/**
 * Supplies the system identity the {@link SystemEventExecutorImpl} records on case events.
 *
 * <p>With a configured {@code id} the identity is fixed at startup. Without one, the identity is
 * looked up from IDAM on first use by signing in with the configured username and password, and is
 * then cached for the life of the application.
 *
 * <p>Only one lookup runs at a time, and a failed lookup is remembered so that callers fail fast
 * instead of each signing in again. A rejected sign-in or an unusable identity is never retried:
 * retrying would only repeat the failure and risk locking the shared system account. Any other
 * failure, such as IDAM being unavailable, is retried once {@link #RETRY_AFTER} has passed.
 */
final class SystemIdentityResolver {

  static final Duration RETRY_AFTER = Duration.ofMinutes(1);

  private final Clock clock;
  private volatile SystemIdentity identity;
  // Guarded by this. Dropped once the lookup can no longer run, so the password is not kept.
  private Supplier<SystemIdentity> lookup;
  private RuntimeException failure;
  private Instant retryAt;

  private SystemIdentityResolver(SystemIdentity identity, Supplier<SystemIdentity> lookup, Clock clock) {
    this.identity = identity;
    this.lookup = lookup;
    this.clock = clock;
  }

  static SystemIdentityResolver fixed(SystemIdentity identity) {
    return new SystemIdentityResolver(identity, null, Clock.systemUTC());
  }

  static SystemIdentityResolver fromIdam(
      IdamService idamService,
      String username,
      String password,
      String firstNameOverride,
      String lastNameOverride,
      Consumer<SystemIdentity> validator
  ) {
    return fromIdam(
        idamService, username, password, firstNameOverride, lastNameOverride, validator, Clock.systemUTC()
    );
  }

  static SystemIdentityResolver fromIdam(
      IdamService idamService,
      String username,
      String password,
      String firstNameOverride,
      String lastNameOverride,
      Consumer<SystemIdentity> validator,
      Clock clock
  ) {
    return new SystemIdentityResolver(null, () -> {
      UserInfo userInfo = idamService.signIn(username, password).userDetails();
      var resolved = new SystemIdentity(
          userInfo.getUid(),
          username,
          hasText(firstNameOverride) ? firstNameOverride : userInfo.getGivenName(),
          hasText(lastNameOverride) ? lastNameOverride : userInfo.getFamilyName()
      );
      validator.accept(resolved);
      return resolved;
    }, clock);
  }

  /** The system identity, looked up from IDAM on the first call if it is not configured. */
  SystemIdentity get() {
    SystemIdentity current = identity;
    if (current != null) {
      return current;
    }
    synchronized (this) {
      if (identity != null) {
        return identity;
      }
      if (failure != null && (retryAt == null || clock.instant().isBefore(retryAt))) {
        throw new IllegalStateException(
            retryAt == null
                ? "The system user could not be looked up from IDAM and will not be retried until restart"
                : "The system user could not be looked up from IDAM; the next attempt is after " + retryAt,
            failure
        );
      }
      try {
        identity = lookup.get();
        lookup = null;
        failure = null;
        return identity;
      } catch (RuntimeException e) {
        failure = e;
        if (isPermanent(e)) {
          retryAt = null;
          lookup = null;
        } else {
          retryAt = clock.instant().plus(RETRY_AFTER);
        }
        throw e;
      }
    }
  }

  /**
   * A rejected sign-in, or an identity IDAM returned that cannot be used, fails the same way
   * every time.
   */
  private static boolean isPermanent(RuntimeException e) {
    if (e instanceof FeignException feign) {
      int status = feign.status();
      return status == 400 || status == 401 || status == 403;
    }
    return e instanceof IllegalArgumentException || e instanceof IllegalStateException;
  }

  record SystemIdentity(String id, String username, String firstName, String lastName) {
  }
}

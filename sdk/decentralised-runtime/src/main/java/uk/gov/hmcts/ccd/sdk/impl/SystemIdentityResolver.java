package uk.gov.hmcts.ccd.sdk.impl;

import static org.springframework.util.StringUtils.hasText;

import feign.FeignException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

/**
 * Supplies the system identity the {@link SystemEventExecutorImpl} records on case events.
 *
 * <p>With a configured {@code id} the identity is fixed at startup. Without one, the identity is
 * looked up from IDAM on first use by signing in with the configured username and password, and is
 * then cached for the life of the application.
 *
 * <p>Only one lookup runs at a time. A caller that arrives while a lookup is running waits up to
 * {@link #LOOKUP_WAIT} for it, then fails rather than queueing behind a slow IDAM. A failed lookup
 * is remembered so that callers fail fast instead of each signing in again. A rejected sign-in or
 * an unusable identity is never retried: retrying would only repeat the failure and risk locking
 * the shared system account. Any other failure, such as IDAM being unavailable, is retried once
 * {@link #RETRY_AFTER} has passed.
 */
@Slf4j
final class SystemIdentityResolver {

  static final Duration RETRY_AFTER = Duration.ofMinutes(1);
  static final Duration LOOKUP_WAIT = Duration.ofSeconds(5);

  private final Clock clock;
  private final Duration lookupWait;
  // Not synchronized: the lookup blocks on IDAM, which would pin a virtual thread's carrier.
  private final ReentrantLock lock = new ReentrantLock();
  private volatile SystemIdentity identity;
  // Guarded by lock. Dropped once the lookup can no longer run, so the password is not kept.
  private Supplier<SystemIdentity> lookup;
  // Guarded by lock. A description, not the exception: a Feign exception keeps the token request,
  // whose body holds the password and client secret.
  private String failure;
  private Instant retryAt;

  private SystemIdentityResolver(
      SystemIdentity identity,
      Supplier<SystemIdentity> lookup,
      Clock clock,
      Duration lookupWait
  ) {
    this.identity = identity;
    this.lookup = lookup;
    this.clock = clock;
    this.lookupWait = lookupWait;
  }

  static SystemIdentityResolver fixed(SystemIdentity identity) {
    return new SystemIdentityResolver(identity, null, Clock.systemUTC(), LOOKUP_WAIT);
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
        idamService, username, password, firstNameOverride, lastNameOverride, validator,
        Clock.systemUTC(), LOOKUP_WAIT
    );
  }

  static SystemIdentityResolver fromIdam(
      IdamService idamService,
      String username,
      String password,
      String firstNameOverride,
      String lastNameOverride,
      Consumer<SystemIdentity> validator,
      Clock clock,
      Duration lookupWait
  ) {
    return new SystemIdentityResolver(null, () -> {
      UserInfo userInfo = idamService.signIn(username, password).userDetails();
      var resolved = new SystemIdentity(
          userInfo.getUid(),
          username,
          hasText(firstNameOverride) ? firstNameOverride : userInfo.getGivenName(),
          hasText(lastNameOverride) ? lastNameOverride : userInfo.getFamilyName()
      );
      try {
        validator.accept(resolved);
      } catch (IllegalArgumentException e) {
        throw new UnusableIdentityException(e.getMessage());
      }
      return resolved;
    }, clock, lookupWait);
  }

  /** The system identity, looked up from IDAM on the first call if it is not configured. */
  SystemIdentity get() {
    SystemIdentity current = identity;
    if (current != null) {
      return current;
    }
    acquireLock();
    try {
      if (identity != null) {
        return identity;
      }
      if (failure != null && (retryAt == null || clock.instant().isBefore(retryAt))) {
        throw new IllegalStateException(failureMessage());
      }
      return lookUp();
    } finally {
      lock.unlock();
    }
  }

  private SystemIdentity lookUp() {
    try {
      SystemIdentity resolved = lookup.get();
      identity = resolved;
      lookup = null;
      failure = null;
      log.info("Looked up system user {} from IDAM: uid {}", resolved.username(), resolved.id());
      return resolved;
    } catch (RuntimeException e) {
      failure = describe(e);
      if (isPermanent(e)) {
        retryAt = null;
        lookup = null;
      } else {
        retryAt = clock.instant().plus(RETRY_AFTER);
      }
      // Only the description: the exception can hold the token request, whose body holds the password.
      log.warn(failureMessage());
      // The cause is attached for this caller only, so the first failure can be diagnosed.
      throw new IllegalStateException(failureMessage(), e);
    }
  }

  private void acquireLock() {
    try {
      if (!lock.tryLock(lookupWait.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException("The system user is still being looked up from IDAM");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for the system user to be looked up", e);
    }
  }

  private String failureMessage() {
    String prefix = "The system user could not be looked up from IDAM (" + failure + ")";
    return retryAt == null
        ? prefix + " and will not be retried until restart"
        : prefix + "; the next attempt is after " + retryAt;
  }

  private static String describe(RuntimeException e) {
    if (e instanceof FeignException feign) {
      return feign.status() > 0 ? "IDAM responded with HTTP " + feign.status() : "IDAM could not be reached";
    }
    return Objects.requireNonNullElse(e.getMessage(), e.getClass().getName());
  }

  /**
   * A rejected sign-in, or an identity IDAM returned that cannot be used, fails the same way
   * every time. Anything else may be transient.
   */
  private static boolean isPermanent(RuntimeException e) {
    if (e instanceof FeignException feign) {
      int status = feign.status();
      return status == 400 || status == 401 || status == 403;
    }
    return e instanceof IdamService.InvalidSignInException || e instanceof UnusableIdentityException;
  }

  record SystemIdentity(String id, String username, String firstName, String lastName) {
  }

  /** The identity IDAM returned fails validation, such as a user with no name. */
  private static final class UnusableIdentityException extends RuntimeException {

    UnusableIdentityException(String message) {
      super(message);
    }
  }
}

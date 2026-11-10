package uk.gov.hmcts.ccd.sdk.impl;

import java.util.function.Consumer;
import java.util.function.Supplier;
import uk.gov.hmcts.reform.idam.client.IdamClient;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

/**
 * Supplies the system identity the {@link SystemEventExecutorImpl} records on case events.
 *
 * <p>With a configured {@code id} the identity is fixed at startup. Without one, the identity is
 * looked up from IDAM on first use by signing in with the configured username and password, and is
 * then cached for the life of the application. A failed lookup is not cached, so the next call
 * retries.
 */
final class SystemIdentityResolver {

  private final Supplier<SystemIdentity> lookup;
  private volatile SystemIdentity identity;

  private SystemIdentityResolver(SystemIdentity identity, Supplier<SystemIdentity> lookup) {
    this.identity = identity;
    this.lookup = lookup;
  }

  static SystemIdentityResolver fixed(SystemIdentity identity) {
    return new SystemIdentityResolver(identity, null);
  }

  static SystemIdentityResolver fromIdam(
      IdamClient idamClient,
      String username,
      String password,
      String firstNameOverride,
      String lastNameOverride
  ) {
    return new SystemIdentityResolver(null, () -> {
      String token = idamClient.getAccessToken(username, password);
      UserInfo userInfo = idamClient.getUserInfo(token);
      if (userInfo == null) {
        throw new IllegalStateException("IDAM returned no user info for the system user");
      }
      return new SystemIdentity(
          userInfo.getUid(),
          username,
          hasText(firstNameOverride) ? firstNameOverride : userInfo.getGivenName(),
          hasText(lastNameOverride) ? lastNameOverride : userInfo.getFamilyName()
      );
    });
  }

  /** The system identity, looked up from IDAM on the first call if it is not configured. */
  SystemIdentity get(Consumer<SystemIdentity> validator) {
    SystemIdentity current = identity;
    if (current != null) {
      return current;
    }
    synchronized (this) {
      if (identity == null) {
        SystemIdentity resolved = lookup.get();
        validator.accept(resolved);
        identity = resolved;
      }
      return identity;
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  record SystemIdentity(String id, String username, String firstName, String lastName) {
  }
}

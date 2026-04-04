package uk.gov.hmcts.ccd.sdk;

import java.util.Optional;
import org.springframework.core.NamedThreadLocal;

/**
 * Exposes request-scoped metadata to callback handlers without changing callback method signatures.
 */
public final class CallbackRequestContext {

  private static final ThreadLocal<String> AUTHORIZATION_TOKEN =
      new NamedThreadLocal<>("ccd-callback-authorization-token");

  private CallbackRequestContext() {
    // Utility class.
  }

  /**
   * Returns the incoming Authorization header for the current request, if present.
   */
  public static Optional<String> getAuthorizationToken() {
    return Optional.ofNullable(AUTHORIZATION_TOKEN.get());
  }

  static void cacheAuthorizationToken(String authorizationToken) {
    if (authorizationToken == null || authorizationToken.isBlank()) {
      AUTHORIZATION_TOKEN.remove();
      return;
    }
    AUTHORIZATION_TOKEN.set(authorizationToken);
  }

  static void clear() {
    AUTHORIZATION_TOKEN.remove();
  }
}

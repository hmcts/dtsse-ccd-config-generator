package uk.gov.hmcts.ccd.sdk.impl;

import java.util.Optional;
import java.util.function.Supplier;

public final class CallbackInvocationContext {

  private static final ThreadLocal<String> AUTHORISATION = new ThreadLocal<>();
  private static final ThreadLocal<String> SERVICE_AUTHORISATION = new ThreadLocal<>();

  private CallbackInvocationContext() {
  }

  public static Optional<String> authorisation() {
    return Optional.ofNullable(AUTHORISATION.get());
  }

  public static Optional<String> serviceAuthorisation() {
    return Optional.ofNullable(SERVICE_AUTHORISATION.get());
  }

  public static <T> T withAuthorisation(String authorisation, Supplier<T> supplier) {
    return withAuthorisation(authorisation, null, supplier);
  }

  public static <T> T withAuthorisation(String authorisation, String serviceAuthorisation, Supplier<T> supplier) {
    String previousAuthorisation = AUTHORISATION.get();
    String previousServiceAuthorisation = SERVICE_AUTHORISATION.get();
    AUTHORISATION.set(authorisation);
    SERVICE_AUTHORISATION.set(serviceAuthorisation);
    try {
      return supplier.get();
    } finally {
      if (previousAuthorisation == null) {
        AUTHORISATION.remove();
      } else {
        AUTHORISATION.set(previousAuthorisation);
      }
      if (previousServiceAuthorisation == null) {
        SERVICE_AUTHORISATION.remove();
      } else {
        SERVICE_AUTHORISATION.set(previousServiceAuthorisation);
      }
    }
  }
}

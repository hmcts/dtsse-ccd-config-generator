package uk.gov.hmcts.ccd.sdk.impl;

import java.util.Optional;
import java.util.function.Supplier;

public final class CallbackInvocationContext {

  private static final ThreadLocal<String> AUTHORISATION = new ThreadLocal<>();

  private CallbackInvocationContext() {
  }

  public static Optional<String> authorisation() {
    return Optional.ofNullable(AUTHORISATION.get());
  }

  public static <T> T withAuthorisation(String authorisation, Supplier<T> supplier) {
    String previous = AUTHORISATION.get();
    AUTHORISATION.set(authorisation);
    try {
      return supplier.get();
    } finally {
      if (previous == null) {
        AUTHORISATION.remove();
      } else {
        AUTHORISATION.set(previous);
      }
    }
  }
}

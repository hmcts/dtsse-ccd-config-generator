package uk.gov.hmcts.ccd.sdk.doctoolkit.cdam;

import java.util.function.Supplier;

/** Supplies the system user's Authorization header for requests to CDAM. */
@FunctionalInterface
public interface CdamSystemUserTokenProvider extends Supplier<String> {
  /**
   * Returns the full Authorization header value, including the {@code Bearer } prefix.
   */
  @Override
  String get();

  /**
   * Invalidates the rejected token before the toolkit calls {@link #get()} to retry a CDAM 401.
   * Implement this if the provider caches tokens and can evict a rejected token.
   */
  default void invalidate(String rejectedToken) {
  }
}

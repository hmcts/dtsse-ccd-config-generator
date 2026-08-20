package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * A {@link BundleDestination} failure carrying an explicit permanence classification, which the
 * rendering pipeline maps onto the error catalogue: a permanent failure becomes
 * {@link BundleErrorCode#STORAGE_REJECTED} (never retried — retrying cannot succeed without a
 * configuration or request change, and each attempt may create a fresh orphaned upload), a
 * non-permanent one becomes {@link BundleErrorCode#STORAGE_FAILED} (eligible for the durable job
 * runner's bounded retry). Destination failures thrown as any other {@code RuntimeException} are
 * treated as transient.
 */
public class BundleStorageException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final boolean permanent;

  /**
   * Creates the exception.
   *
   * @param message the log-safe description of the failure
   * @param permanent whether the destination rejected the artifact permanently
   */
  public BundleStorageException(String message, boolean permanent) {
    super(message);
    this.permanent = permanent;
  }

  /**
   * Creates the exception with a log-safe cause.
   *
   * @param message the log-safe description of the failure
   * @param permanent whether the destination rejected the artifact permanently
   * @param cause the underlying failure; must not carry secrets or raw response bodies
   */
  public BundleStorageException(String message, boolean permanent, Throwable cause) {
    super(message, cause);
    this.permanent = permanent;
  }

  /**
   * Whether the destination rejected the artifact permanently, so a retry with the same
   * configuration and request cannot succeed.
   *
   * @return true for a permanent rejection
   */
  public boolean isPermanent() {
    return permanent;
  }
}

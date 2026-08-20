package uk.gov.hmcts.ccd.sdk.bundling.api;

import java.time.Duration;

/**
 * Configurable maxima enforced by the renderer, each breached with a descriptive
 * {@link BundleErrorCode#LIMIT_EXCEEDED} or {@link BundleErrorCode#TIMED_OUT} error.
 *
 * <p>{@link #defaults()} carries the initial working targets from the design document; the
 * empirical ceilings are unresolved, so raising a target later is a configuration and
 * test-fixture change, not a redesign.
 *
 * @param maxDocumentCount the maximum number of documents in one request
 * @param maxSourceBytesPerDocument the maximum size of one non-media source document
 * @param maxOfficeSourceBytesPerDocument the maximum size of one office-format source sent for
 *     conversion
 * @param maxOutputBytes the maximum size of the finished bundle
 * @param maxTotalPages the maximum total page count of the finished bundle
 * @param maxElapsed the hard end-to-end timeout, covering every stage
 */
public record BundleLimits(
    int maxDocumentCount,
    long maxSourceBytesPerDocument,
    long maxOfficeSourceBytesPerDocument,
    long maxOutputBytes,
    int maxTotalPages,
    Duration maxElapsed) {

  private static final long MEGABYTE = 1024L * 1024L;

  public BundleLimits {
    requirePositive(maxDocumentCount, "maxDocumentCount");
    requirePositive(maxSourceBytesPerDocument, "maxSourceBytesPerDocument");
    requirePositive(maxOfficeSourceBytesPerDocument, "maxOfficeSourceBytesPerDocument");
    requirePositive(maxOutputBytes, "maxOutputBytes");
    requirePositive(maxTotalPages, "maxTotalPages");
    Validate.requireNonNull(maxElapsed, "BundleLimits.maxElapsed");
    if (maxElapsed.isZero() || maxElapsed.isNegative()) {
      throw new IllegalArgumentException("BundleLimits.maxElapsed must be positive");
    }
  }

  /**
   * The initial working targets: 100 documents, 300 MB per source, 50 MB per office source,
   * 1 GB output, 1,000 pages, one minute end-to-end.
   *
   * @return the default limits
   */
  public static BundleLimits defaults() {
    return new BundleLimits(
        100,
        300 * MEGABYTE,
        50 * MEGABYTE,
        1024 * MEGABYTE, 1000,
        Duration.ofMinutes(1));
  }

  private static void requirePositive(long value, String field) {
    if (value <= 0) {
      throw new IllegalArgumentException("BundleLimits." + field + " must be positive");
    }
  }
}

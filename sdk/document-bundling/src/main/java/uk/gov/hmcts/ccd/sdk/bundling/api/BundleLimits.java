package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * Hard limits enforced by the renderer. Breaching one fails the bundle with
 * {@link BundleErrorCode#LIMIT_EXCEEDED}; nothing is produced.
 */
public record BundleLimits(
    int maxDocumentCount,
    long maxSourceBytesPerDocument,
    long maxOutputBytes,
    int maxTotalPages) {

  private static final long MEGABYTE = 1024L * 1024L;

  public BundleLimits {
    requirePositive(maxDocumentCount, "maxDocumentCount");
    requirePositive(maxSourceBytesPerDocument, "maxSourceBytesPerDocument");
    requirePositive(maxOutputBytes, "maxOutputBytes");
    requirePositive(maxTotalPages, "maxTotalPages");
  }

  /** The defaults: 100 documents, 300 MB per source, 1 GB output, 1,000 pages. */
  public static BundleLimits defaults() {
    return new BundleLimits(100, 300 * MEGABYTE, 1024 * MEGABYTE, 1000);
  }

  private static void requirePositive(long value, String field) {
    if (value <= 0) {
      throw new IllegalArgumentException("BundleLimits." + field + " must be positive");
    }
  }
}

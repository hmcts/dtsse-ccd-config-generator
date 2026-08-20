package uk.gov.hmcts.ccd.sdk.bundling.docmosis;

import java.net.URI;
import java.time.Duration;

/**
 * Connection settings for the shared Docmosis render service, bound from whichever properties the
 * consuming service already has ({@code DOCMOSIS_*} in EM-style services, {@code TORNADO_*} in
 * ET-style services).
 *
 * <p>Every call the client makes is bounded by these settings: connection and read timeouts, a
 * source-size ceiling enforced before anything is sent, and a small retry budget spent on
 * transient failures only. {@link #toString()} redacts the access key so the record is safe to
 * log.
 *
 * @param convertEndpoint the absolute {@code /rs/convert} URI for file-to-PDF conversion
 * @param renderEndpoint the absolute {@code /rs/render} URI for template rendering
 * @param accessKey the shared platform access key; never logged or echoed in errors
 * @param connectTimeout how long to wait for a connection to be established
 * @param readTimeout how long to wait for the complete response
 * @param maxSourceBytes the largest source file the client will send for conversion
 * @param retryAttempts how many times a transient failure is retried (0 to {@value
 *     #MAX_RETRY_ATTEMPTS})
 */
public record DocmosisConnection(
    URI convertEndpoint,
    URI renderEndpoint,
    String accessKey,
    Duration connectTimeout,
    Duration readTimeout,
    long maxSourceBytes,
    int retryAttempts) {

  /** Default connection timeout. */
  public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

  /** Default read timeout. */
  public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(60);

  /** Default source-size ceiling: 100 MiB. */
  public static final long DEFAULT_MAX_SOURCE_BYTES = 100L * 1024 * 1024;

  /** Default number of retries of a transient failure. */
  public static final int DEFAULT_RETRY_ATTEMPTS = 1;

  /** The largest timeout either bound may be configured to. */
  public static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);

  /** The largest permitted retry budget. */
  public static final int MAX_RETRY_ATTEMPTS = 5;

  /**
   * Validates the settings. Messages never include the access key.
   *
   * @param convertEndpoint the absolute {@code /rs/convert} URI
   * @param renderEndpoint the absolute {@code /rs/render} URI
   * @param accessKey the shared platform access key
   * @param connectTimeout the connection timeout
   * @param readTimeout the read timeout
   * @param maxSourceBytes the source-size ceiling
   * @param retryAttempts the transient-failure retry budget
   */
  public DocmosisConnection {
    requireAbsolute("convertEndpoint", convertEndpoint);
    requireAbsolute("renderEndpoint", renderEndpoint);
    if (accessKey == null || accessKey.isBlank()) {
      throw new IllegalArgumentException("accessKey must be provided");
    }
    requireBoundedTimeout("connectTimeout", connectTimeout);
    requireBoundedTimeout("readTimeout", readTimeout);
    if (maxSourceBytes <= 0) {
      throw new IllegalArgumentException("maxSourceBytes must be positive, was " + maxSourceBytes);
    }
    if (retryAttempts < 0 || retryAttempts > MAX_RETRY_ATTEMPTS) {
      throw new IllegalArgumentException(
          "retryAttempts must be between 0 and " + MAX_RETRY_ATTEMPTS + ", was " + retryAttempts);
    }
  }

  /**
   * Creates a connection with the default timeouts, size ceiling, and retry budget.
   *
   * @param convertEndpoint the absolute {@code /rs/convert} URI
   * @param renderEndpoint the absolute {@code /rs/render} URI
   * @param accessKey the shared platform access key
   * @return a connection bounded by the defaults
   */
  public static DocmosisConnection withDefaults(
      URI convertEndpoint, URI renderEndpoint, String accessKey) {
    return new DocmosisConnection(
        convertEndpoint,
        renderEndpoint,
        accessKey,
        DEFAULT_CONNECT_TIMEOUT,
        DEFAULT_READ_TIMEOUT,
        DEFAULT_MAX_SOURCE_BYTES,
        DEFAULT_RETRY_ATTEMPTS);
  }

  private static void requireAbsolute(String name, URI endpoint) {
    if (endpoint == null || !endpoint.isAbsolute()) {
      throw new IllegalArgumentException(name + " must be an absolute URI, was " + endpoint);
    }
  }

  private static void requireBoundedTimeout(String name, Duration timeout) {
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive, was " + timeout);
    }
    if (timeout.compareTo(MAX_TIMEOUT) > 0) {
      throw new IllegalArgumentException(
          name + " must not exceed " + MAX_TIMEOUT + ", was " + timeout);
    }
  }

  /**
   * Describes the connection with the access key redacted, so the record is safe to log.
   *
   * @return the settings with {@code accessKey=<redacted>}
   */
  @Override
  public String toString() {
    return "DocmosisConnection[convertEndpoint=" + convertEndpoint
        + ", renderEndpoint=" + renderEndpoint
        + ", accessKey=<redacted>"
        + ", connectTimeout=" + connectTimeout
        + ", readTimeout=" + readTimeout
        + ", maxSourceBytes=" + maxSourceBytes
        + ", retryAttempts=" + retryAttempts + "]";
  }
}

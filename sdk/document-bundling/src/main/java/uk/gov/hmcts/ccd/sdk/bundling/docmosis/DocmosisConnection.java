package uk.gov.hmcts.ccd.sdk.bundling.docmosis;

import java.net.URI;
import java.time.Duration;

/**
 * Connection settings for the shared Docmosis render service, bound from
 * {@code ccd.bundling.docmosis.*} (or built directly for non-Spring use).
 *
 * <p>Every call the client makes is bounded by these settings: connection and read timeouts and
 * a source-size ceiling enforced before anything is sent. {@link #toString()} redacts the access
 * key so the record is safe to log.
 */
public record DocmosisConnection(
    URI convertEndpoint,
    String accessKey,
    Duration connectTimeout,
    Duration readTimeout,
    long maxSourceBytes) {

  /** Default connection timeout. */
  public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

  /** Default read timeout. */
  public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(60);

  /** Default source-size ceiling: 100 MiB. */
  public static final long DEFAULT_MAX_SOURCE_BYTES = 100L * 1024 * 1024;

  /** The largest timeout either bound may be configured to. */
  public static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);


  /** Validates the settings. Messages never include the access key. */
  public DocmosisConnection {
    requireAbsolute("convertEndpoint", convertEndpoint);
    if (accessKey == null || accessKey.isBlank()) {
      throw new IllegalArgumentException("accessKey must be provided");
    }
    requireBoundedTimeout("connectTimeout", connectTimeout);
    requireBoundedTimeout("readTimeout", readTimeout);
    if (maxSourceBytes <= 0) {
      throw new IllegalArgumentException("maxSourceBytes must be positive, was " + maxSourceBytes);
    }
  }

  /** Creates a connection with the default timeouts and size ceiling. */
  public static DocmosisConnection withDefaults(URI convertEndpoint, String accessKey) {
    return new DocmosisConnection(
        convertEndpoint, accessKey, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT,
        DEFAULT_MAX_SOURCE_BYTES);
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

  /** Describes the connection with the access key redacted, so the record is safe to log. */
  @Override
  public String toString() {
    return "DocmosisConnection[convertEndpoint=" + convertEndpoint
        + ", accessKey=<redacted>"
        + ", connectTimeout=" + connectTimeout
        + ", readTimeout=" + readTimeout
        + ", maxSourceBytes=" + maxSourceBytes + "]";
  }
}

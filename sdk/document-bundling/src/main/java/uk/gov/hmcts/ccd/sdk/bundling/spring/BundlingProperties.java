package uk.gov.hmcts.ccd.sdk.bundling.spring;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleLimits;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisConnection;

/**
 * {@code ccd.bundling.*} properties. Unset values fall back to the module defaults
 * ({@link BundleLimits#defaults()}, {@link DocmosisConnection#withDefaults}).
 */
@Data
@ConfigurationProperties(prefix = "ccd.bundling")
public class BundlingProperties {

  // Master switch: false registers nothing.
  private boolean enabled = true;
  // Concurrent renders permitted in this JVM; excess renders block.
  private int maxConcurrentRenders = 2;
  // Base directory for per-render temporary directories; defaults to java.io.tmpdir.
  private Path tempDirectory;
  private Docmosis docmosis = new Docmosis();
  private Limits limits = new Limits();

  /** Docmosis connection; office conversion is registered only when both values are set. */
  @Data
  public static class Docmosis {
    private URI convertEndpoint;
    @ToString.Exclude
    private String accessKey;
    private Duration connectTimeout = DocmosisConnection.DEFAULT_CONNECT_TIMEOUT;
    private Duration readTimeout = DocmosisConnection.DEFAULT_READ_TIMEOUT;
    private long maxSourceBytes = DocmosisConnection.DEFAULT_MAX_SOURCE_BYTES;

    DocmosisConnection toConnection() {
      return new DocmosisConnection(
          convertEndpoint, accessKey, connectTimeout, readTimeout, maxSourceBytes);
    }
  }

  /** Per-field overrides of the default bundle limits. */
  @Data
  public static class Limits {
    private int maxDocumentCount = BundleLimits.defaults().maxDocumentCount();
    private long maxSourceBytesPerDocument = BundleLimits.defaults().maxSourceBytesPerDocument();
    private long maxOutputBytes = BundleLimits.defaults().maxOutputBytes();
    private int maxTotalPages = BundleLimits.defaults().maxTotalPages();

    BundleLimits toLimits() {
      return new BundleLimits(
          maxDocumentCount, maxSourceBytesPerDocument, maxOutputBytes, maxTotalPages);
    }
  }
}

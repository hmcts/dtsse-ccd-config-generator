package uk.gov.hmcts.ccd.sdk.conversion.spring;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import uk.gov.hmcts.ccd.sdk.conversion.docmosis.DocmosisConnection;

/**
 * {@code ccd.conversion.*} properties.
 */
@Data
@ConfigurationProperties(prefix = "ccd.conversion")
public class ConversionProperties {

  // Master switch: false registers nothing.
  private boolean enabled = true;
  // Base directory for per-conversion working directories; defaults to java.io.tmpdir.
  private Path tempDirectory;
  private Docmosis docmosis = new Docmosis();

  /**
   * Docmosis connection for office conversion. Ignored when the application already has a
   * {@code DocmosisRenderService} bean, such as the one document bundling configures.
   */
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
}

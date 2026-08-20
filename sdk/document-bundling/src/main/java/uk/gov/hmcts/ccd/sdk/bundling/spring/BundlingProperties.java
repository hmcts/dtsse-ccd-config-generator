package uk.gov.hmcts.ccd.sdk.bundling.spring;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleLimits;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisConnection;
import uk.gov.hmcts.reform.ccd.document.am.model.Classification;

/**
 * Configuration for the document-bundling module, bound from {@code ccd.bundling}.
 *
 * <p>Every block is optional. The Docmosis block enables the default office-format handlers when
 * its three connection properties are present; the CDAM block (together with a
 * {@code CaseDocumentClientApi} bean and a
 * {@link uk.gov.hmcts.ccd.sdk.bundling.cdam.BundlingAuthenticationProvider} bean) enables the
 * built-in CDAM destination and resolver; the limits block overrides
 * {@link BundleLimits#defaults()} field by field. The Docmosis access key is excluded from
 * {@link #toString()} so the bound properties are safe to log.
 */
@Data
@ConfigurationProperties(prefix = "ccd.bundling")
public class BundlingProperties {

  /**
   * Whether the bundling auto-configuration is active; {@code false} registers nothing.
   */
  private boolean enabled = true;

  /**
   * The maximum number of bundles rendered concurrently in this JVM; excess submissions queue.
   * Unset uses the renderer builder's deliberately small default of 2, because rendering runs in
   * the consumer's JVM.
   */
  private Integer maxConcurrentRenders;

  /**
   * The base directory for the module's temporary files: each render's owner-only, job-scoped
   * directory, the Docmosis output directory, and the CDAM spool directory. Unset uses
   * {@code java.io.tmpdir}.
   */
  private Path tempDirectory;

  /** The shared Docmosis render service connection. */
  private Docmosis docmosis = new Docmosis();

  /** The built-in CDAM destination's upload coordinates. */
  private Cdam cdam = new Cdam();

  /** Per-field overrides of the renderer's default limits. */
  private Limits limits = new Limits();

  /**
   * Connection settings for the shared Docmosis render service, bound from
   * {@code ccd.bundling.docmosis}. Bind the three connection properties to whichever environment
   * variables the consuming service already has ({@code DOCMOSIS_*} in EM-style services,
   * {@code TORNADO_*} in ET-style services). When all three are present the auto-configuration
   * registers the Docmosis-backed office handlers; when any is absent, office media types stay
   * unhandled and a bundle containing one fails with a descriptive error.
   */
  @Data
  public static class Docmosis {

    /**
     * The absolute {@code /rs/convert} URI for file-to-PDF conversion.
     */
    private URI convertEndpoint;

    /**
     * The absolute {@code /rs/render} URI for template rendering.
     */
    private URI renderEndpoint;

    /**
     * The shared platform access key. Never logged, echoed in errors, or included in
     * {@link #toString()}.
     */
    @ToString.Exclude
    private String accessKey;

    /**
     * How long to wait for a connection to be established. Unset uses
     * {@link DocmosisConnection#DEFAULT_CONNECT_TIMEOUT}.
     */
    private Duration connectTimeout;

    /**
     * How long to wait for the complete response, streamed body included. Unset uses
     * {@link DocmosisConnection#DEFAULT_READ_TIMEOUT}.
     */
    private Duration readTimeout;

    /**
     * The largest source file sent for conversion, enforced before anything is sent. Unset uses
     * {@link DocmosisConnection#DEFAULT_MAX_SOURCE_BYTES}.
     */
    private Long maxSourceBytes;

    /**
     * How many times a transient failure is retried; 4xx responses are never retried. Unset uses
     * {@link DocmosisConnection#DEFAULT_RETRY_ATTEMPTS}.
     */
    private Integer retryAttempts;
  }

  /**
   * The built-in CDAM destination's upload coordinates, bound from {@code ccd.bundling.cdam}.
   * All three are required when the block is used and none is defaulted — in particular the
   * classification is explicit by design, because an adapter must never default a bundle
   * containing restricted material to public.
   */
  @Data
  public static class Cdam {

    /** The CCD jurisdiction id the stored bundle is uploaded under. */
    private String jurisdictionId;

    /** The CCD case type id the stored bundle is uploaded under. */
    private String caseTypeId;

    /** The explicit security classification of the stored bundle. */
    private Classification classification;

    /**
     * Whether the destination attaches the uploaded document to the execution context's case
     * through CDAM's {@code attachToCase}, immediately after the upload. Off by default: it is
     * the shape for decentralised submit-handler events and requires the service's S2S identity
     * to hold CDAM {@code ATTACH} permission; services that persist the bundle reference through
     * a platform-scanned case-data path (legacy callbacks) leave this off. See
     * {@link uk.gov.hmcts.ccd.sdk.bundling.cdam.CdamUploadSettings}.
     */
    private boolean attachToCase;
  }

  /**
   * Per-field overrides of {@link BundleLimits#defaults()}, bound from
   * {@code ccd.bundling.limits}. Each unset field keeps its default; every set field must satisfy
   * {@link BundleLimits}' own validation.
   */
  @Data
  public static class Limits {

    /** The maximum number of documents in one request. */
    private Integer maxDocumentCount;

    /** The maximum size in bytes of one non-media source document. */
    private Long maxSourceBytesPerDocument;

    /** The maximum size in bytes of one office-format source sent for conversion. */
    private Long maxOfficeSourceBytesPerDocument;

    /** The maximum size in bytes of the finished bundle. */
    private Long maxOutputBytes;

    /** The maximum total page count of the finished bundle. */
    private Integer maxTotalPages;

    /** The hard end-to-end timeout, covering every stage. */
    private Duration maxElapsed;
  }
}

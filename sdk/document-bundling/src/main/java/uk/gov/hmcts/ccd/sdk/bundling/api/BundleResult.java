package uk.gov.hmcts.ccd.sdk.bundling.api;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A successfully rendered bundle. The finished PDF lives in a renderer-owned temporary directory
 * that is deleted when the result is closed, so read or copy {@link #artifact()} inside a
 * try-with-resources block.
 */
public final class BundleResult implements AutoCloseable {

  private final BundleOutcome outcome;
  private final BundleArtifact artifact;
  private final List<BundleWarning> warnings;
  private final List<DocumentResult> documents;
  private final List<MissingDocument> missingDocuments;
  private final Map<BundleStage, Duration> timings;
  private final Runnable cleanup;

  public BundleResult(
      BundleArtifact artifact,
      List<BundleWarning> warnings,
      List<DocumentResult> documents,
      Map<BundleStage, Duration> timings,
      Runnable cleanup) {
    this(artifact, warnings, documents, List.of(), timings, cleanup);
  }

  /** A result in which some documents were replaced by placeholder pages. */
  public BundleResult(
      BundleArtifact artifact,
      List<BundleWarning> warnings,
      List<DocumentResult> documents,
      List<MissingDocument> missingDocuments,
      Map<BundleStage, Duration> timings,
      Runnable cleanup) {
    this.missingDocuments = List.copyOf(
        Validate.requireNonNull(missingDocuments, "BundleResult.missingDocuments"));
    this.artifact = Validate.requireNonNull(artifact, "BundleResult.artifact");
    this.warnings = List.copyOf(Validate.requireNonNull(warnings, "BundleResult.warnings"));
    this.documents = List.copyOf(Validate.requireNonNull(documents, "BundleResult.documents"));
    this.timings = Map.copyOf(Validate.requireNonNull(timings, "BundleResult.timings"));
    this.cleanup = Validate.requireNonNull(cleanup, "BundleResult.cleanup");
    this.outcome = this.warnings.isEmpty() && this.missingDocuments.isEmpty()
        ? BundleOutcome.COMPLETED : BundleOutcome.COMPLETED_WITH_WARNINGS;
  }

  public BundleOutcome outcome() {
    return outcome;
  }

  /** The finished PDF. */
  public BundleArtifact artifact() {
    return artifact;
  }

  public int pageCount() {
    return artifact.pageCount();
  }

  /** Non-fatal presentational notes; never an omitted document. */
  public List<BundleWarning> warnings() {
    return warnings;
  }

  /** Where each included request document landed, in render order. */
  public List<DocumentResult> documents() {
    return documents;
  }

  /**
   * The request documents replaced by placeholder pages, in render order. Always empty under
   * {@link MissingDocumentPolicy#FAIL}.
   */
  public List<MissingDocument> missingDocuments() {
    return missingDocuments;
  }

  /** Wall-clock time spent in each pipeline stage. */
  public Map<BundleStage, Duration> timings() {
    return timings;
  }

  /** Deletes the finished PDF and every intermediate file. Idempotent. */
  @Override
  public void close() {
    cleanup.run();
  }
}

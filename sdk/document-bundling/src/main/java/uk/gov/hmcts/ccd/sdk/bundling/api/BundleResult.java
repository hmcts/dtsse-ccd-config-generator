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
  private final Map<BundleStage, Duration> timings;
  private final Runnable cleanup;

  public BundleResult(
      BundleArtifact artifact,
      List<BundleWarning> warnings,
      List<DocumentResult> documents,
      Map<BundleStage, Duration> timings,
      Runnable cleanup) {
    this.artifact = Validate.requireNonNull(artifact, "BundleResult.artifact");
    this.warnings = List.copyOf(Validate.requireNonNull(warnings, "BundleResult.warnings"));
    this.documents = List.copyOf(Validate.requireNonNull(documents, "BundleResult.documents"));
    this.timings = Map.copyOf(Validate.requireNonNull(timings, "BundleResult.timings"));
    this.cleanup = Validate.requireNonNull(cleanup, "BundleResult.cleanup");
    this.outcome = this.warnings.isEmpty()
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

  /** Where each request document landed, in render order. */
  public List<DocumentResult> documents() {
    return documents;
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

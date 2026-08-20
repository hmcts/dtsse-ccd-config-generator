package uk.gov.hmcts.ccd.sdk.bundling.api;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The immutable result of a successful bundle generation.
 *
 * <p>{@link #output()} is what the consumer attaches to its case: the platform-standard,
 * {@code CcdBundleDTO}-compatible bundle with the stitched document's CDAM links inside.
 * {@link #stored()} carries the storage facts — links, size, checksum — and together with the
 * warnings and per-document entries forms the generation report: the audit-grade record of what
 * was stitched, in what order, with what checksums, from which the consumer can create an
 * adequate audit event.
 *
 * @param outcome the successful outcome
 * @param output the CCD-shaped bundle to attach to the case
 * @param stored the stored artifact's links, size, and checksum
 * @param pageCount the total page count of the output
 * @param warnings non-fatal presentational notes, never omitted documents
 * @param documents the generation report entries, in bundle order
 * @param timings elapsed time per pipeline stage
 */
public record BundleResult(
    BundleOutcome outcome,
    CcdBundle output,
    StoredBundle stored,
    int pageCount,
    List<BundleWarning> warnings,
    List<DocumentResult> documents,
    Map<BundleStage, Duration> timings) {

  public BundleResult {
    Validate.requireNonNull(outcome, "BundleResult.outcome");
    Validate.requireNonNull(output, "BundleResult.output");
    Validate.requireNonNull(stored, "BundleResult.stored");
    if (pageCount < 1) {
      throw new IllegalArgumentException("BundleResult.pageCount must be at least 1");
    }
    warnings = List.copyOf(Validate.requireNonNull(warnings, "BundleResult.warnings"));
    documents = List.copyOf(Validate.requireNonNull(documents, "BundleResult.documents"));
    timings = Map.copyOf(Validate.requireNonNull(timings, "BundleResult.timings"));
  }
}

package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.List;
import java.util.Objects;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleOutcome;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleWarning;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.MissingDocument;

/**
 * What a completed job rendered, recorded with its completed state from the same render the
 * completion handler stored. For a selector-driven job the request is the one the selector
 * compiled, so it lists exactly the documents the bundle was built from.
 *
 * @param request the request that was rendered: folders, documents and presentation
 * @param outcome whether the render completed cleanly or with warnings
 * @param fileName the finished PDF's file name
 * @param size the finished PDF's size in bytes
 * @param sha256 the finished PDF's SHA-256 checksum
 * @param pageCount the finished PDF's page count
 * @param documents where each included document landed
 * @param missingDocuments the documents replaced by placeholder pages, and why
 * @param warnings non-fatal notes from the render
 */
public record BundleJobReport(
    BundleRequest request,
    BundleOutcome outcome,
    String fileName,
    long size,
    String sha256,
    int pageCount,
    List<DocumentResult> documents,
    List<MissingDocument> missingDocuments,
    List<BundleWarning> warnings) {

  public BundleJobReport {
    Objects.requireNonNull(request, "BundleJobReport.request");
    Objects.requireNonNull(outcome, "BundleJobReport.outcome");
    Objects.requireNonNull(fileName, "BundleJobReport.fileName");
    Objects.requireNonNull(sha256, "BundleJobReport.sha256");
    documents = List.copyOf(documents);
    missingDocuments = List.copyOf(missingDocuments);
    warnings = List.copyOf(warnings);
  }

  static BundleJobReport of(BundleRequest request, BundleResult result) {
    return new BundleJobReport(request, result.outcome(), result.artifact().fileName(),
        result.artifact().size(), result.artifact().sha256(), result.pageCount(),
        result.documents(), result.missingDocuments(), result.warnings());
  }
}

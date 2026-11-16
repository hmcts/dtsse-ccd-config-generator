package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleOutcome;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleWarning;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.MissingDocument;

/**
 * A bundle the job worker rendered, read from bundling.bundle. The worker records it when the job
 * completes, so the consuming service stores only the PDF (as an ordinary document, linked by the
 * job id) and reads everything else about the bundle from here.
 *
 * @param id the bundle's id
 * @param jobId the job that rendered it
 * @param definition the bundle definition's name
 * @param caseKey the definition's key for the case
 * @param caseReference the reference of the case the documents were selected for
 * @param selectedAt when its documents were selected; of two bundles with the same key, the one
 *     selected later reflects the newer state
 * @param recordedAt when the bundle was recorded
 * @param request the request that was rendered: for a selector-driven job, the one the selector
 *     compiled, so it lists exactly the documents the bundle was built from
 * @param outcome whether the render completed cleanly or with warnings
 * @param fileName the finished PDF's file name
 * @param mediaType the finished PDF's media type
 * @param size the finished PDF's size in bytes
 * @param sha256 the finished PDF's SHA-256 checksum
 * @param pageCount the finished PDF's page count
 * @param documents where each included document landed
 * @param missingDocuments the documents replaced by placeholder pages, and why
 * @param warnings non-fatal notes from the render
 * @param stored where the completion handler stored the PDF, if it said
 */
public record BundleMetadata(
    UUID id,
    UUID jobId,
    String definition,
    String caseKey,
    long caseReference,
    Instant selectedAt,
    Instant recordedAt,
    BundleRequest request,
    BundleOutcome outcome,
    String fileName,
    String mediaType,
    long size,
    String sha256,
    int pageCount,
    List<DocumentResult> documents,
    List<MissingDocument> missingDocuments,
    List<BundleWarning> warnings,
    Optional<StoredBundleDocument> stored) {

  public BundleMetadata {
    Objects.requireNonNull(id, "BundleMetadata.id");
    Objects.requireNonNull(jobId, "BundleMetadata.jobId");
    Objects.requireNonNull(definition, "BundleMetadata.definition");
    Objects.requireNonNull(caseKey, "BundleMetadata.caseKey");
    Objects.requireNonNull(selectedAt, "BundleMetadata.selectedAt");
    Objects.requireNonNull(recordedAt, "BundleMetadata.recordedAt");
    Objects.requireNonNull(request, "BundleMetadata.request");
    Objects.requireNonNull(outcome, "BundleMetadata.outcome");
    Objects.requireNonNull(fileName, "BundleMetadata.fileName");
    Objects.requireNonNull(mediaType, "BundleMetadata.mediaType");
    Objects.requireNonNull(sha256, "BundleMetadata.sha256");
    Objects.requireNonNull(stored, "BundleMetadata.stored");
    documents = List.copyOf(documents);
    missingDocuments = List.copyOf(missingDocuments);
    warnings = List.copyOf(warnings);
  }
}

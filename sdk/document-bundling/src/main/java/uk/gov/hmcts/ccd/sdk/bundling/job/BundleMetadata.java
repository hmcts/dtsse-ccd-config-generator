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
 * completes, so the consuming service stores only the PDF (as an ordinary document) and reads
 * everything else about the bundle from here.
 *
 * @param id the bundle's id
 * @param definition the bundle definition's name
 * @param caseReference the case it is a bundle of
 * @param selectedAt when the render that selected its documents was claimed; a case's bundles
 *     are rendered one at a time, so the one selected last is current
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
    String definition,
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
    Objects.requireNonNull(definition, "BundleMetadata.definition");
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

package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleOutcome;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlePresentation;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleStage;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleWarning;
import uk.gov.hmcts.ccd.sdk.bundling.api.CcdBundle;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.EmptySectionPolicy;
import uk.gov.hmcts.ccd.sdk.bundling.api.MediaPlaceholder;
import uk.gov.hmcts.ccd.sdk.bundling.api.PageNumbers;
import uk.gov.hmcts.ccd.sdk.bundling.api.StoredBundle;
import uk.gov.hmcts.ccd.sdk.type.Document;

/** Shared builders for durable-job tests. */
final class BundleJobFixtures {

  private BundleJobFixtures() {
  }

  /** A minimal one-document request. */
  static BundleRequest simpleRequest(UUID externalId) {
    return BundleRequest.builder()
        .externalId(externalId)
        .title("Hearing bundle")
        .fileName("hearing-bundle.pdf")
        .root(BundleSection.builder("Case file")
            .document(BundleDocument.builder()
                .id("doc-1")
                .title("Application form")
                .reference(new DocumentReference("case-documents", "d1"))
                .build())
            .build())
        .build();
  }

  /**
   * A request exercising the whole tree: nested sections, dates, confidentiality, a media
   * placeholder, an empty placeholder section, and a non-default presentation.
   */
  static BundleRequest fullRequest(UUID externalId) {
    return BundleRequest.builder()
        .externalId(externalId)
        .title("Final hearing bundle")
        .fileName("final-hearing-bundle.pdf")
        .presentation(BundlePresentation.courtDefault()
            .withDocumentCoverSheets(true)
            .withPageNumbers(PageNumbers.TOP_RIGHT_N_OF_M))
        .root(BundleSection.builder("Case file")
            .document(BundleDocument.builder()
                .id("doc-1")
                .title("Application form")
                .date(LocalDate.of(2026, 3, 14))
                .reference(new DocumentReference("case-documents", "d1"))
                .build())
            .section(BundleSection.builder("Evidence")
                .document(BundleDocument.builder()
                    .id("doc-2")
                    .title("Medical report")
                    .date(LocalDate.of(2026, 5, 2))
                    .confidential(true)
                    .reference(new DocumentReference("case-documents", "d2"))
                    .build())
                .document(BundleDocument.builder()
                    .id("doc-3")
                    .title("Hearing recording")
                    .reference(new DocumentReference("media-store", "m1"))
                    .media(MediaPlaceholder.builder()
                        .accessUrl("https://media.example.net/recordings/m1")
                        .duration(Duration.ofMinutes(42))
                        .note("Playback requires case-worker sign-in")
                        .build())
                    .build())
                .build())
            .section(BundleSection.builder("Orders")
                .emptySectionPolicy(EmptySectionPolicy.INCLUDE_PLACEHOLDER)
                .build())
            .build())
        .build();
  }

  /** A populated non-secret execution context. */
  static BundleExecutionContext context() {
    return BundleExecutionContext.builder()
        .caseReference("1234567890123456")
        .initiator("system-scheduler")
        .attribute("hearingId", "H-77")
        .attribute("jurisdiction", "ST_CIC")
        .build();
  }

  /** A plausible successful render result for the given request. */
  static BundleResult resultFor(BundleRequest request, BundleOutcome outcome) {
    List<DocumentResult> documents = new ArrayList<>();
    int startPage = 1;
    for (BundleDocument document : request.allDocuments()) {
      documents.add(new DocumentResult(
          document.id(), document.reference(), "application/pdf", "sha-" + document.id(),
          2, startPage));
      startPage += 2;
    }
    CcdBundle output = CcdBundle.builder()
        .id(request.externalId().toString())
        .title(request.title())
        .fileName(request.fileName())
        .stitchStatus("DONE")
        .stitchedDocument(Document.builder()
            .url("http://dm-store/documents/out")
            .binaryUrl("http://dm-store/documents/out/binary")
            .filename(request.fileName())
            .build())
        .build();
    StoredBundle stored = new StoredBundle(
        "http://dm-store/documents/out", "http://dm-store/documents/out/binary",
        request.fileName(), "application/pdf", 4096L, "output-sha", Optional.empty());
    List<BundleWarning> warnings = outcome == BundleOutcome.COMPLETED_WITH_WARNINGS
        ? List.of(BundleWarning.of("EMPTY_SECTION_PAGE", "An empty-section page was included"))
        : List.of();
    return new BundleResult(outcome, output, stored, Math.max(1, startPage - 1), warnings,
        documents, Map.of(BundleStage.ASSEMBLE, Duration.ofMillis(5)));
  }
}

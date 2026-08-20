package uk.gov.hmcts.ccd.sdk.bundling.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlePresentation;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.EmptySectionPolicy;
import uk.gov.hmcts.ccd.sdk.bundling.api.PageNumbers;

/**
 * The outbox persists submitted requests as JSON, so the full request tree — sections,
 * documents, media placeholders, presentation — and the execution context must round-trip
 * through {@link BundleJobJson} with their semantics intact.
 */
class BundleRequestJsonRoundTripTest {

  private final BundleJobJson json = new BundleJobJson();
  private final ObjectMapper treeReader = new ObjectMapper();

  @Test
  void theFullRequestTreeRoundTripsWithEqualSemantics() throws Exception {
    UUID externalId = UUID.randomUUID();
    BundleRequest original = BundleJobFixtures.fullRequest(externalId);

    String serialised = json.writeRequest(original);
    BundleRequest read = json.readRequest(serialised);

    assertThat(read.externalId()).isEqualTo(externalId);
    assertThat(read.title()).isEqualTo("Final hearing bundle");
    assertThat(read.fileName()).isEqualTo("final-hearing-bundle.pdf");

    // Presentation, including the non-default choices.
    assertThat(read.presentation()).isEqualTo(BundlePresentation.courtDefault()
        .withDocumentCoverSheets(true)
        .withPageNumbers(PageNumbers.TOP_RIGHT_N_OF_M));

    // The ordered tree.
    BundleSection root = read.root();
    assertThat(root.title()).isEqualTo("Case file");
    assertThat(root.documents()).hasSize(1);
    assertThat(root.sections()).extracting(BundleSection::title)
        .containsExactly("Evidence", "Orders");
    assertThat(root.sections().get(1).emptySectionPolicy())
        .isEqualTo(EmptySectionPolicy.INCLUDE_PLACEHOLDER);
    assertThat(read.allDocuments()).extracting(BundleDocument::id)
        .containsExactly("doc-1", "doc-2", "doc-3");

    // Document metadata.
    BundleDocument doc1 = root.documents().get(0);
    assertThat(doc1.title()).isEqualTo("Application form");
    assertThat(doc1.date()).contains(LocalDate.of(2026, 3, 14));
    assertThat(doc1.reference()).isEqualTo(new DocumentReference("case-documents", "d1"));
    assertThat(doc1.confidential()).isFalse();
    assertThat(doc1.media()).isEmpty();

    BundleDocument doc2 = root.sections().get(0).documents().get(0);
    assertThat(doc2.confidential()).isTrue();
    assertThat(doc2.date()).contains(LocalDate.of(2026, 5, 2));

    // The media placeholder.
    BundleDocument doc3 = root.sections().get(0).documents().get(1);
    assertThat(doc3.media()).isPresent();
    assertThat(doc3.media().orElseThrow().accessUrl())
        .isEqualTo("https://media.example.net/recordings/m1");
    assertThat(doc3.media().orElseThrow().duration()).contains(Duration.ofMinutes(42));
    assertThat(doc3.media().orElseThrow().note())
        .contains("Playback requires case-worker sign-in");

    // A second pass produces the identical JSON: the round trip is lossless and stable.
    assertThat(treeReader.readTree(json.writeRequest(read)))
        .isEqualTo(treeReader.readTree(serialised));
  }

  @Test
  void theExecutionContextRoundTripsIncludingAttributes() {
    BundleExecutionContext original = BundleJobFixtures.context();

    BundleExecutionContext read = json.readContext(json.writeContext(original));

    assertThat(read.caseReference()).contains("1234567890123456");
    assertThat(read.initiator()).contains("system-scheduler");
    assertThat(read.attributes())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("hearingId", "H-77", "jurisdiction", "ST_CIC"));
  }

  @Test
  void anEmptyExecutionContextRoundTrips() {
    BundleExecutionContext read =
        json.readContext(json.writeContext(BundleExecutionContext.empty()));

    assertThat(read.caseReference()).isEmpty();
    assertThat(read.initiator()).isEmpty();
    assertThat(read.attributes()).isEmpty();
  }

  @Test
  void selectorParametersRoundTrip() {
    Map<String, String> parameters = Map.of("caseReference", "42", "hearingId", "H-1");

    assertThat(json.readParameters(json.writeParameters(parameters))).isEqualTo(parameters);
  }

  @Test
  void aRequestThatCannotBeReadRaisesThePayloadException() {
    assertThatThrownBy(() -> json.readRequest("\"not-a-request\""))
        .isInstanceOf(BundleJobPayloadException.class)
        .hasMessageContaining("bundle request");
  }

  @Test
  void transientHistoryRoundTripsAndDegradesToEmptyWhenUnreadable() {
    List<BundleJobTransientFailure> history = List.of(
        new BundleJobTransientFailure(1,
            uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode.STORAGE_FAILED,
            "CDAM upload failed", java.time.Instant.parse("2026-08-13T09:00:00Z")));

    assertThat(json.readHistory(json.writeHistory(history))).isEqualTo(history);
    assertThat(json.readHistory("not json")).isEmpty();
    assertThat(json.readHistory(null)).isEmpty();
  }
}

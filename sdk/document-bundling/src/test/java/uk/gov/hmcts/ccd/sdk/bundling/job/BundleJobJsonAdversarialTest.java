package uk.gov.hmcts.ccd.sdk.bundling.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;

/**
 * Adversarial round-trip attacks on the outbox wire format. Disabled tests encode the desired
 * behaviour and fail against the current implementation; the finding reference names the defect.
 */
class BundleJobJsonAdversarialTest {

  private final BundleJobJson json = new BundleJobJson();

  // ---------------------------------------------------------------------------------------------
  // FINDING 7 (fixed by prevention): attributes flatten with @JsonAnyGetter into the same JSON
  // object as the declared caseReference/initiator properties, so an attribute under either name
  // could hijack the persisted correlation identity on round-trip. The builder now rejects the
  // reserved keys outright, so the colliding context can never be constructed, persisted, or
  // round-tripped; the flattened JSON shape is unchanged for every other key.
  // ---------------------------------------------------------------------------------------------

  @Test
  void anAttributeNamedCaseReferenceIsRejectedSoItCanNeverHijackTheDeclaredField() {
    assertThatThrownBy(() -> BundleExecutionContext.builder()
        .caseReference("1111222233334444")
        .attribute("caseReference", "smuggled-override"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("caseReference")
        .hasMessageContaining("reserved");
  }

  @Test
  void anAttributeNamedInitiatorIsRejectedSoItCanNeverHijackTheDeclaredField() {
    assertThatThrownBy(() -> BundleExecutionContext.builder()
        .initiator("system-scheduler")
        .attribute("initiator", "someone-else"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("initiator")
        .hasMessageContaining("reserved");
  }

  @Test
  void nonReservedAttributesStillRoundTripInTheFlattenedShape() {
    BundleExecutionContext original = BundleExecutionContext.builder()
        .caseReference("1111222233334444")
        .initiator("system-scheduler")
        .attribute("hearingId", "H-42")
        .build();

    BundleExecutionContext read = json.readContext(json.writeContext(original));

    assertThat(read.caseReference()).contains("1111222233334444");
    assertThat(read.initiator()).contains("system-scheduler");
    assertThat(read.attributes()).containsEntry("hearingId", "H-42");
  }

  // ---------------------------------------------------------------------------------------------
  // Enabled guards: shapes that do round-trip correctly.
  // ---------------------------------------------------------------------------------------------

  @Test
  void unicodeTitlesAndFileNamesRoundTripIntact() {
    UUID id = UUID.randomUUID();
    String title = "Bwndel gwrandawiad — dogfennau'r llys 中文 📄";
    BundleRequest original = BundleRequest.builder()
        .externalId(id)
        .title(title)
        .fileName("bwndel-âêî.pdf")
        .root(BundleSection.builder("Adran â'r llys")
            .document(BundleDocument.builder()
                .id("doc-ü")
                .title("Týstýsgrif")
                .reference(new DocumentReference("case-documents", "d1"))
                .build())
            .build())
        .build();

    BundleRequest read = json.readRequest(json.writeRequest(original));

    assertThat(read.title()).isEqualTo(title);
    assertThat(read.fileName()).isEqualTo("bwndel-âêî.pdf");
    assertThat(read.root().title()).isEqualTo("Adran â'r llys");
    assertThat(read.root().documents().get(0).id()).isEqualTo("doc-ü");
  }

  @Test
  void aReadBackRequestStillEnforcesBuildValidation() {
    // The builder-based deserialiser runs build(), so a tampered stored request with duplicate
    // document ids is rejected as unreadable rather than silently rendered.
    UUID id = UUID.randomUUID();
    String valid = json.writeRequest(BundleJobFixtures.simpleRequest(id));
    String tampered = valid.replace("\"id\":\"doc-1\"", "\"id\":\"doc-1\"")
        .replace("\"documents\":[", "\"documents\":["
            + "{\"id\":\"doc-1\",\"title\":\"dup\",\"date\":null,"
            + "\"reference\":{\"provider\":\"case-documents\",\"id\":\"dx\"},"
            + "\"confidential\":false,\"media\":null},");

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> json.readRequest(tampered))
        .isInstanceOf(BundleJobPayloadException.class);
  }
}

package uk.gov.hmcts.ccd.sdk.bundling.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;

class BundleDocumentSelectorTest {

  private static BundleRequest request(UUID externalId) {
    return BundleRequest.builder()
        .externalId(externalId)
        .title("Hearing bundle")
        .fileName("bundle.pdf")
        .root(BundleSection.builder("Case file")
            .document(BundleDocument.builder()
                .id("doc-1")
                .title("Application")
                .reference(new DocumentReference("case-documents", "doc-1"))
                .build())
            .build())
        .build();
  }

  @Test
  void theDefaultSelectorReturnsTheRequestExactlyAsSubmitted() {
    UUID externalId = UUID.randomUUID();
    BundleRequest submitted = request(externalId);
    BundleJobContext context = new BundleJobContext(
        externalId, Optional.of(submitted), Map.of(), BundleExecutionContext.empty());

    assertThat(BundleDocumentSelector.asSubmitted().select(context)).isSameAs(submitted);
  }

  @Test
  void theDefaultSelectorFailsClearlyWhenOnlyParametersWereSubmitted() {
    UUID externalId = UUID.randomUUID();
    BundleJobContext context = new BundleJobContext(
        externalId, Optional.empty(), Map.of("caseReference", "1234"),
        BundleExecutionContext.empty());

    assertThatThrownBy(() -> BundleDocumentSelector.asSubmitted().select(context))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(externalId.toString())
        .hasMessageContaining("BundleDocumentSelector");
  }
}

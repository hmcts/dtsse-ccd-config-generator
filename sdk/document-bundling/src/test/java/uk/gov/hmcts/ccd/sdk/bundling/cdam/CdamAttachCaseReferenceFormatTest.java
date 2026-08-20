package uk.gov.hmcts.ccd.sdk.bundling.cdam;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;
import uk.gov.hmcts.reform.ccd.document.am.model.Classification;

/**
 * The pre-upload case-reference gate covers format, not just presence: CDAM's
 * {@code attachToCase} validates {@code caseId} as exactly 16 digits
 * ({@code @Size(min=16,max=16)} + {@code ^[0-9]*$} on the real API's
 * {@code CaseDocumentsMetadata}), so a reference CDAM must reject fails here BEFORE anything is
 * uploaded — otherwise the artifact would be uploaded, the attach would 400, and each retry
 * would orphan a fresh upload.
 */
class CdamAttachCaseReferenceFormatTest {

  private final CaseDocumentClientApi client = mock(CaseDocumentClientApi.class);
  private final BundlingAuthenticationProvider authentication =
      mock(BundlingAuthenticationProvider.class);
  private final CdamBundleDestination destination = new CdamBundleDestination(
      client, authentication,
      new CdamUploadSettings("BBA3", "Benefit", Classification.RESTRICTED, true));
  private final InMemoryBundleArtifact artifact = new InMemoryBundleArtifact(
      "hearing-bundle.pdf", "pdf-bytes".getBytes(StandardCharsets.UTF_8));

  @BeforeEach
  void stubTokens() {
    when(authentication.systemUserToken()).thenReturn("Bearer user-token");
    when(authentication.serviceToken()).thenReturn("service-token");
  }

  @Test
  void aMalformedCaseReferenceFailsBeforeAnythingIsUploaded() {
    assertThatThrownBy(() -> destination.store(
        artifact,
        BundleExecutionContext.builder().caseReference("not-a-case-reference").build()))
        .isInstanceOf(CdamUploadException.class)
        .hasMessageContaining("caseReference")
        .hasMessageContaining("CDAM was not called");
    verifyNoInteractions(client);
  }
}

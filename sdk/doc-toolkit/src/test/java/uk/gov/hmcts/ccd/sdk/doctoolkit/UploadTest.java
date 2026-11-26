package uk.gov.hmcts.ccd.sdk.doctoolkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.SERVICE_TOKENS;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.SYSTEM_USER;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.assertNothingLeftBehind;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.fixture;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.doctoolkit.cdam.CaseLocator;
import uk.gov.hmcts.ccd.sdk.doctoolkit.support.FakeCdam;
import uk.gov.hmcts.reform.ccd.document.am.model.DocumentHashToken;

class UploadTest {
  private static final long CASE_ID = 1234567890123456L;

  @TempDir
  private Path work;

  private final FakeCdam cdam = new FakeCdam();
  private DocToolkit toolkit;
  private UUID source;

  @BeforeEach
  void setUp() {
    CaseLocator cases = caseId -> {
      assertThat(caseId).isEqualTo(CASE_ID);
      return new CaseLocator.CaseType("PCS", "POSSESSIONS");
    };
    toolkit = DocToolkit.builder().tempDirectory(work)
        .cdam(cdam, SERVICE_TOKENS, SYSTEM_USER, cases).build();
    source = cdam.store(fixture("one-page.pdf"), "application/pdf", "claim.pdf");
  }

  @Test
  void storesTheResultAsTheSystemUserAndAttachesItToTheCase() throws Exception {
    PdfRequest request = PdfRequest.builder("Case bundle")
        .fileName("1234-case-bundle.pdf")
        .document(DocSource.cdam(source))
        .build();

    UploadedDoc uploaded = toolkit.upload(request, CASE_ID);

    FakeCdam.Upload upload = cdam.uploads.getFirst();
    assertThat(upload.authorisation()).isEqualTo("Bearer system-user");
    assertThat(upload.classification()).isEqualTo("PUBLIC");
    assertThat(upload.caseTypeId()).isEqualTo("PCS");
    assertThat(upload.jurisdiction()).isEqualTo("POSSESSIONS");
    assertThat(upload.fileName()).isEqualTo("1234-case-bundle.pdf");
    assertThat(upload.contentType()).isEqualTo("application/pdf");
    try (PDDocument stored = Loader.loadPDF(upload.content())) {
      assertThat(stored.getNumberOfPages()).isEqualTo(uploaded.pageCount());
    }
    assertThat(cdam.attachments).singleElement().satisfies(attached -> {
      assertThat(attached.getCaseId()).isEqualTo(String.valueOf(CASE_ID));
      assertThat(attached.getCaseTypeId()).isEqualTo("PCS");
      assertThat(attached.getJurisdictionId()).isEqualTo("POSSESSIONS");
      assertThat(attached.getDocumentHashTokens()).containsExactly(
          new DocumentHashToken(upload.id().toString(), upload.hashToken()));
    });
    assertThat(uploaded.document().getUrl()).isEqualTo(FakeCdam.HOST + upload.id());
    assertThat(uploaded.document().getBinaryUrl()).isEqualTo(FakeCdam.HOST + upload.id() + "/binary");
    assertThat(uploaded.document().getFilename()).isEqualTo("1234-case-bundle.pdf");
    assertThat(uploaded.document().getUploadTimestamp()).isNotNull();
    assertThat(uploaded.document().getCategoryId()).isNull();
    assertThat(uploaded.failures()).isEmpty();
    assertNothingLeftBehind(work);
  }

  @Test
  void theRequestsCategoryAndClassificationGoWithTheUpload() {
    PdfRequest request = PdfRequest.builder("Hearing bundle")
        .document(DocSource.cdam(source))
        .category("bundles")
        .classification(Classification.RESTRICTED)
        .build();

    UploadedDoc uploaded = toolkit.upload(request, CASE_ID);

    assertThat(cdam.uploads).singleElement().satisfies(upload ->
        assertThat(upload.classification()).isEqualTo("RESTRICTED"));
    assertThat(uploaded.document().getCategoryId()).isEqualTo("bundles");
  }

  @Test
  void aDocumentThatCannotBeAttachedIsLeftUnattachedToExpireAndTheUploadFails() {
    cdam.failAttaching(new IllegalStateException("CDAM refused the hash token"));
    PdfRequest request = PdfRequest.builder("Claim").document(DocSource.cdam(source)).build();

    assertThatThrownBy(() -> toolkit.upload(request, CASE_ID))
        .isInstanceOfSatisfying(DocToolkitException.class, e -> {
          assertThat(e.reason()).isEqualTo(DocToolkitException.Reason.UPLOAD_FAILED);
          assertThat(e.isTransient()).isTrue();
        });
    assertThat(cdam.uploads).hasSize(1);
    assertThat(cdam.attachments).isEmpty();
    assertNothingLeftBehind(work);
  }

  @Test
  void uploadingWithoutCdamIsNotConfigured() {
    DocToolkit local = DocToolkit.builder().tempDirectory(work).build();
    PdfRequest request = PdfRequest.builder("One page")
        .document(DocSource.bytes(fixture("one-page.pdf"), "one-page.pdf")).build();

    assertThatThrownBy(() -> local.upload(request, CASE_ID))
        .isInstanceOfSatisfying(DocToolkitException.class, e ->
            assertThat(e.reason()).isEqualTo(DocToolkitException.Reason.NOT_CONFIGURED));
    assertThat(List.of(cdam.uploads, cdam.attachments)).allMatch(List::isEmpty);
  }
}

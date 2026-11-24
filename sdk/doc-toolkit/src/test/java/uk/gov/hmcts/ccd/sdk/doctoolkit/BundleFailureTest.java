package uk.gov.hmcts.ccd.sdk.doctoolkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.ONE_CASE_TYPE;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.SERVICE_TOKENS;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.SYSTEM_USER;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.assertNothingLeftBehind;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.fixture;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.load;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.outline;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocFailure.Reason;
import uk.gov.hmcts.ccd.sdk.doctoolkit.convert.testsupport.Fixtures;
import uk.gov.hmcts.ccd.sdk.doctoolkit.convert.testsupport.StubDocmosis;
import uk.gov.hmcts.ccd.sdk.doctoolkit.docmosis.DocmosisRenderException;
import uk.gov.hmcts.ccd.sdk.doctoolkit.support.FakeCdam;

class BundleFailureTest {
  private static final String DOCX =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

  @TempDir
  private Path work;
  @TempDir
  private Path sources;

  private final FakeCdam cdam = new FakeCdam();
  private DocToolkit toolkit;
  private UUID present;

  @BeforeEach
  void setUp() {
    toolkit = toolkit(null);
    present = cdam.store(fixture("one-page.pdf"), "application/pdf", "present.pdf");
  }

  private DocToolkit toolkit(StubDocmosis docmosis) {
    DocToolkit.Builder builder = DocToolkit.builder().tempDirectory(work)
        .cdam(cdam, SERVICE_TOKENS, SYSTEM_USER, ONE_CASE_TYPE);
    if (docmosis != null) {
      builder.docmosis(docmosis);
    }
    return builder.build();
  }

  @Test
  void documentsThatCannotBeFetchedAreReplacedByPagesSayingWhy() throws Exception {
    BundleRequest request = BundleRequest.builder("Bundle")
        .section(BundleSection.builder("Evidence")
            .document(BundleDocument.builder(DocSource.cdam(present)).title("Present").build())
            .document(BundleDocument.builder(DocSource.cdam(cdam.failing(404))).title("Deleted").build())
            .document(BundleDocument.builder(DocSource.cdam(cdam.failing(403))).title("Forbidden").build())
            .document(BundleDocument.builder(DocSource.cdam(cdam.failing(503))).title("Outage").build())
            .build())
        .build();

    try (DocResult result = toolkit.render(request); PDDocument document = load(result)) {
      assertThat(result.failures()).extracting(DocFailure::title, DocFailure::reason).containsExactly(
          tuple("Deleted", Reason.NOT_FOUND), tuple("Forbidden", Reason.ACCESS_DENIED),
          tuple("Outage", Reason.UNAVAILABLE));
      assertThat(result.failures()).extracting(failure -> failure.reason().isTransient())
          .containsExactly(false, false, true);
      assertThat(text(document)).contains(Reason.NOT_FOUND.message(),
          Reason.ACCESS_DENIED.message(), Reason.UNAVAILABLE.message());
      assertThat(outline(document)).contains("Present", "Deleted (missing)", "Forbidden (missing)",
          "Outage (missing)");
    }
    assertNothingLeftBehind(work);
  }

  @Test
  void documentsThatCannotBeConvertedAreReplacedByPagesSayingWhy() throws Exception {
    Path encrypted = Fixtures.encryptedPdf(sources);
    Path zip = Fixtures.zip(sources);
    BundleRequest request = BundleRequest.builder("Bundle")
        .document(BundleDocument.builder(DocSource.file(encrypted)).title("Encrypted").build())
        .document(BundleDocument.builder(DocSource.file(zip, "statement.pdf", "application/pdf"))
            .title("Not a PDF").build())
        .document(BundleDocument.builder(DocSource.file(zip)).title("Archive").build())
        .build();

    try (DocResult result = toolkit.render(request)) {
      assertThat(result.failures()).extracting(DocFailure::title, DocFailure::reason).containsExactly(
          tuple("Encrypted", Reason.UNREADABLE), tuple("Not a PDF", Reason.UNREADABLE),
          tuple("Archive", Reason.UNSUPPORTED_FORMAT));
      assertThat(result.failures()).allSatisfy(failure -> assertThat(failure.detail()).isNotBlank());
    }
  }

  @Test
  void aDocmosisOutageIsUnavailableAndARejectionIsAConversionFailure() throws Exception {
    Path word = Files.write(sources.resolve("statement.docx"), fixture("wordDocument2.docx"));
    BundleRequest request = BundleRequest.builder("Bundle")
        .document(DocSource.file(word, "statement.docx", DOCX))
        .build();

    try (DocResult result = toolkit(StubDocmosis.failing(
        new DocmosisRenderException("Docmosis returned 503", true))).render(request)) {
      assertThat(result.failures()).extracting(DocFailure::reason).containsExactly(Reason.UNAVAILABLE);
    }
    try (DocResult result = toolkit(StubDocmosis.failing(
        new DocmosisRenderException("Docmosis returned 400", false))).render(request)) {
      assertThat(result.failures()).extracting(DocFailure::reason)
          .containsExactly(Reason.CONVERSION_FAILED);
    }
  }

  @Test
  void aBundleOfOnlyMissingDocumentsStillRenders() throws Exception {
    BundleRequest request = BundleRequest.builder("Bundle")
        .document(BundleDocument.builder(DocSource.cdam(cdam.failing(404))).title("Gone").build())
        .build();

    try (DocResult result = toolkit.render(request); PDDocument document = load(result)) {
      assertThat(result.failures()).hasSize(1);
      assertThat(text(document)).contains("This document is missing from the bundle");
    }
  }

  @Test
  void theFailPolicyFailsTheBundleListingEveryMissingDocument() {
    BundleRequest request = BundleRequest.builder("Bundle")
        .document(BundleDocument.builder(DocSource.cdam(present)).title("Present").build())
        .document(BundleDocument.builder(DocSource.cdam(cdam.failing(503))).title("Outage").build())
        .document(BundleDocument.builder(DocSource.cdam(cdam.failing(404))).title("Gone").build())
        .missingDocuments(MissingDocuments.FAIL)
        .build();

    assertThatThrownBy(() -> toolkit.render(request))
        .isInstanceOfSatisfying(DocToolkitException.class, e -> {
          assertThat(e.reason()).isEqualTo(DocToolkitException.Reason.DOCUMENTS_FAILED);
          assertThat(e.failures()).extracting(DocFailure::title).containsExactly("Outage", "Gone");
          // One permanent failure means retrying will not help.
          assertThat(e.isTransient()).isFalse();
          assertThat(e.getMessage()).contains("Outage (UNAVAILABLE)", "Gone (NOT_FOUND)");
        });
    assertNothingLeftBehind(work);
  }

  @Test
  void anOfficeDocumentWithoutDocmosisFailsTheBundle() throws Exception {
    Path word = Files.write(sources.resolve("statement.docx"), fixture("wordDocument2.docx"));
    BundleRequest request = BundleRequest.builder("Bundle")
        .document(DocSource.file(word, "statement.docx", DOCX))
        .build();

    assertThatThrownBy(() -> toolkit.render(request))
        .isInstanceOfSatisfying(DocToolkitException.class, e -> {
          assertThat(e.reason()).isEqualTo(DocToolkitException.Reason.NOT_CONFIGURED);
          assertThat(e.getMessage()).contains("ccd.doc-toolkit.docmosis.convert-endpoint");
        });
    assertNothingLeftBehind(work);
  }

  @Test
  void aCdamDocumentWithoutCdamFailsTheBundle() {
    DocToolkit withoutCdam = DocToolkit.builder().tempDirectory(work).build();
    BundleRequest request = BundleRequest.builder("Bundle").document(DocSource.cdam(present)).build();

    assertThatThrownBy(() -> withoutCdam.render(request))
        .isInstanceOfSatisfying(DocToolkitException.class, e -> {
          assertThat(e.reason()).isEqualTo(DocToolkitException.Reason.NOT_CONFIGURED);
          assertThat(e.getMessage()).contains("DocToolkitSystemUser");
        });
  }
}

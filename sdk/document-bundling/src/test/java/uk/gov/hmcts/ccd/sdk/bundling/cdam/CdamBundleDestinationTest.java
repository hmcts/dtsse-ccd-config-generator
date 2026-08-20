package uk.gov.hmcts.ccd.sdk.bundling.cdam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import static org.mockito.Mockito.never;

import feign.FeignException;
import feign.Request;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.multipart.MultipartFile;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleArtifact;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.StoredBundle;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;
import uk.gov.hmcts.reform.ccd.document.am.model.CaseDocumentsMetadata;
import uk.gov.hmcts.reform.ccd.document.am.model.Classification;
import uk.gov.hmcts.reform.ccd.document.am.model.Document;
import uk.gov.hmcts.reform.ccd.document.am.model.DocumentUploadRequest;
import uk.gov.hmcts.reform.ccd.document.am.model.UploadResponse;

class CdamBundleDestinationTest {

  private static final byte[] PDF_BYTES = "pdf-bytes".getBytes(StandardCharsets.UTF_8);
  private static final String DOCUMENT_ID = "b1d3fabb-9e5a-4f4b-97cc-6ab1f6ee91c4";
  private static final String SELF_HREF = "http://dm-store/documents/" + DOCUMENT_ID;
  private static final String BINARY_HREF = "http://dm-store/documents/" + DOCUMENT_ID + "/binary";
  private static final String CASE_REFERENCE = "1234123412341234";

  private final CaseDocumentClientApi client = mock(CaseDocumentClientApi.class);
  private final BundlingAuthenticationProvider authentication = mock(BundlingAuthenticationProvider.class);
  private final CdamUploadSettings settings =
      new CdamUploadSettings("BBA3", "Benefit", Classification.RESTRICTED);
  private final CdamBundleDestination destination =
      new CdamBundleDestination(client, authentication, settings);
  private final InMemoryBundleArtifact artifact =
      new InMemoryBundleArtifact("hearing-bundle.pdf", PDF_BYTES);

  @BeforeEach
  void stubTokens() {
    when(authentication.systemUserToken()).thenReturn("Bearer user-token");
    when(authentication.serviceToken()).thenReturn("service-token");
  }

  @Test
  void uploadsTheArtifactAndMapsTheStoredDocument() {
    when(client.uploadDocuments(any(), any(), any()))
        .thenReturn(uploadResponse(document(SELF_HREF, BINARY_HREF, "hash-token")));

    StoredBundle stored = destination.store(artifact, BundleExecutionContext.empty());

    assertThat(stored.url()).isEqualTo(SELF_HREF);
    assertThat(stored.binaryUrl()).isEqualTo(BINARY_HREF);
    assertThat(stored.filename()).isEqualTo("hearing-bundle.pdf");
    assertThat(stored.mediaType()).isEqualTo("application/pdf");
    assertThat(stored.size()).isEqualTo(PDF_BYTES.length);
    assertThat(stored.sha256()).isEqualTo("test-sha256");
    assertThat(stored.hashToken()).contains("hash-token");
  }

  @Test
  void hashTokenIsEmptyWhenTheResponseCarriesNone() {
    when(client.uploadDocuments(any(), any(), any()))
        .thenReturn(uploadResponse(document(SELF_HREF, BINARY_HREF, null)));

    StoredBundle stored = destination.store(artifact, BundleExecutionContext.empty());

    assertThat(stored.hashToken()).isEmpty();
  }

  @Test
  void hashTokenIsEmptyWhenTheResponseCarriesABlankOne() {
    when(client.uploadDocuments(any(), any(), any()))
        .thenReturn(uploadResponse(document(SELF_HREF, BINARY_HREF, " ")));

    StoredBundle stored = destination.store(artifact, BundleExecutionContext.empty());

    assertThat(stored.hashToken()).isEmpty();
  }

  @Test
  void passesTheConfiguredClassificationAndCoordinatesThroughExactly() throws IOException {
    when(client.uploadDocuments(any(), any(), any()))
        .thenReturn(uploadResponse(document(SELF_HREF, BINARY_HREF, null)));

    destination.store(artifact, BundleExecutionContext.empty());

    ArgumentCaptor<DocumentUploadRequest> request = ArgumentCaptor.forClass(DocumentUploadRequest.class);
    verify(client).uploadDocuments(eq("Bearer user-token"), eq("service-token"), request.capture());
    assertThat(request.getValue().getClassification()).isEqualTo("RESTRICTED");
    assertThat(request.getValue().getJurisdictionId()).isEqualTo("BBA3");
    assertThat(request.getValue().getCaseTypeId()).isEqualTo("Benefit");
    assertThat(request.getValue().getFiles()).hasSize(1);
    MultipartFile file = request.getValue().getFiles().get(0);
    assertThat(file.getOriginalFilename()).isEqualTo("hearing-bundle.pdf");
    assertThat(file.getContentType()).isEqualTo("application/pdf");
    assertThat(file.getBytes()).isEqualTo(PDF_BYTES);
  }

  @Test
  void throwsDescriptivelyWhenTheResponseHasNoDocuments() {
    when(client.uploadDocuments(any(), any(), any())).thenReturn(new UploadResponse(List.of()));

    assertThatThrownBy(() -> destination.store(artifact, BundleExecutionContext.empty()))
        .isInstanceOf(CdamUploadException.class)
        .hasMessageContaining("hearing-bundle.pdf")
        .hasMessageContaining("no document metadata");
  }

  @Test
  void throwsDescriptivelyWhenTheResponseHasNoLinks() {
    Document document = Document.builder().hashToken("hash").build();
    when(client.uploadDocuments(any(), any(), any())).thenReturn(uploadResponse(document));

    assertThatThrownBy(() -> destination.store(artifact, BundleExecutionContext.empty()))
        .isInstanceOf(CdamUploadException.class)
        .hasMessageContaining("hearing-bundle.pdf")
        .hasMessageContaining("self link");
  }

  @Test
  void surfacesUploadFailuresDescriptivelyWithoutTheRawResponseBody() {
    Request feignRequest = Request.create(
        Request.HttpMethod.POST, "/cases/documents", Map.of(), null, StandardCharsets.UTF_8, null);
    when(client.uploadDocuments(any(), any(), any())).thenThrow(new FeignException.InternalServerError(
        "boom", feignRequest, "raw downstream error body".getBytes(StandardCharsets.UTF_8), Map.of()));

    assertThatThrownBy(() -> destination.store(artifact, BundleExecutionContext.empty()))
        .isInstanceOf(CdamUploadException.class)
        .hasMessageContaining("hearing-bundle.pdf")
        .hasMessageContaining("500")
        .hasNoCause()
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("raw downstream error body"))
        // A 5xx is transient: the durable job runner may retry it.
        .satisfies(e -> assertThat(((CdamUploadException) e).isPermanent()).isFalse());
  }

  @Test
  void aClientErrorUploadFailureIsPermanent() {
    Request feignRequest = Request.create(
        Request.HttpMethod.POST, "/cases/documents", Map.of(), null, StandardCharsets.UTF_8, null);
    when(client.uploadDocuments(any(), any(), any())).thenThrow(new FeignException.Forbidden(
        "boom", feignRequest, new byte[0], Map.of()));

    assertThatThrownBy(() -> destination.store(artifact, BundleExecutionContext.empty()))
        .isInstanceOf(CdamUploadException.class)
        .hasMessageContaining("403")
        // A 4xx is a permanent rejection: retrying unchanged cannot succeed.
        .satisfies(e -> assertThat(((CdamUploadException) e).isPermanent()).isTrue());
  }

  @Test
  void throwsDescriptivelyWhenTheArtifactCannotBeRead() {
    assertThatThrownBy(() -> destination.store(failingArtifact(), BundleExecutionContext.empty()))
        .isInstanceOf(CdamUploadException.class)
        .hasMessageContaining("Could not read bundle artifact");
  }

  @Test
  void refusesToUploadAZeroByteArtifact() {
    InMemoryBundleArtifact empty = new InMemoryBundleArtifact("hearing-bundle.pdf", new byte[0]);

    assertThatThrownBy(() -> destination.store(empty, BundleExecutionContext.empty()))
        .isInstanceOf(CdamUploadException.class)
        .hasMessageContaining("zero-byte");
    verifyNoInteractions(client);
  }

  @Test
  void classificationMustBeExplicit() {
    assertThatThrownBy(() -> new CdamUploadSettings("BBA3", "Benefit", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("classification must be explicit");
  }

  @Test
  void withoutAttachToCaseNoAttachCallIsMade() {
    when(client.uploadDocuments(any(), any(), any()))
        .thenReturn(uploadResponse(document(SELF_HREF, BINARY_HREF, "hash-token")));

    destination.store(artifact, contextWithCase());

    verify(client, never()).patchDocument(any(), any(), any(CaseDocumentsMetadata.class));
  }

  @Test
  void attachToCasePatchesTheUploadedDocumentOntoTheContextsCase() {
    when(client.uploadDocuments(any(), any(), any()))
        .thenReturn(uploadResponse(document(SELF_HREF, BINARY_HREF, "hash-token")));

    StoredBundle stored = attachingDestination().store(artifact, contextWithCase());

    ArgumentCaptor<CaseDocumentsMetadata> metadata = ArgumentCaptor.forClass(CaseDocumentsMetadata.class);
    verify(client).patchDocument(eq("Bearer user-token"), eq("service-token"), metadata.capture());
    assertThat(metadata.getValue().getCaseId()).isEqualTo(CASE_REFERENCE);
    assertThat(metadata.getValue().getCaseTypeId()).isEqualTo("Benefit");
    assertThat(metadata.getValue().getJurisdictionId()).isEqualTo("BBA3");
    assertThat(metadata.getValue().getDocumentHashTokens()).hasSize(1);
    assertThat(metadata.getValue().getDocumentHashTokens().get(0).getId()).isEqualTo(DOCUMENT_ID);
    assertThat(metadata.getValue().getDocumentHashTokens().get(0).getHashToken()).isEqualTo("hash-token");
    assertThat(stored.url()).isEqualTo(SELF_HREF);
    assertThat(stored.hashToken()).contains("hash-token");
  }

  @Test
  void attachToCasePassesANullHashTokenWhenTheUploadReturnedNone() {
    when(client.uploadDocuments(any(), any(), any()))
        .thenReturn(uploadResponse(document(SELF_HREF, BINARY_HREF, null)));

    attachingDestination().store(artifact, contextWithCase());

    ArgumentCaptor<CaseDocumentsMetadata> metadata = ArgumentCaptor.forClass(CaseDocumentsMetadata.class);
    verify(client).patchDocument(any(), any(), metadata.capture());
    assertThat(metadata.getValue().getDocumentHashTokens().get(0).getHashToken()).isNull();
  }

  @Test
  void attachToCaseWithoutACaseReferenceFailsBeforeAnythingIsUploaded() {
    assertThatThrownBy(() -> attachingDestination().store(artifact, BundleExecutionContext.empty()))
        .isInstanceOf(CdamUploadException.class)
        .hasMessageContaining("caseReference")
        .hasMessageContaining("CDAM was not called")
        .satisfies(e -> assertThat(((CdamUploadException) e).isPermanent()).isTrue());
    verifyNoInteractions(client);
  }

  @Test
  void attachFailuresSurfaceDescriptivelyWithoutTheRawResponseBody() {
    when(client.uploadDocuments(any(), any(), any()))
        .thenReturn(uploadResponse(document(SELF_HREF, BINARY_HREF, "hash-token")));
    Request feignRequest = Request.create(
        Request.HttpMethod.PATCH, "/cases/documents/attachToCase", Map.of(), null,
        StandardCharsets.UTF_8, null);
    when(client.patchDocument(any(), any(), any(CaseDocumentsMetadata.class)))
        .thenThrow(new FeignException.Forbidden(
            "boom", feignRequest, "raw attach error body".getBytes(StandardCharsets.UTF_8), Map.of()));

    assertThatThrownBy(() -> attachingDestination().store(artifact, contextWithCase()))
        .isInstanceOf(CdamUploadException.class)
        .hasMessageContaining("hearing-bundle.pdf")
        .hasMessageContaining(CASE_REFERENCE)
        .hasMessageContaining("403")
        .hasMessageContaining("ATTACH permission")
        .hasNoCause()
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("raw attach error body"))
        // A 4xx attach rejection is permanent: a retry would only orphan a fresh upload.
        .satisfies(e -> assertThat(((CdamUploadException) e).isPermanent()).isTrue());
  }

  private CdamBundleDestination attachingDestination() {
    return new CdamBundleDestination(client, authentication,
        new CdamUploadSettings("BBA3", "Benefit", Classification.RESTRICTED, true));
  }

  private static BundleExecutionContext contextWithCase() {
    return BundleExecutionContext.builder().caseReference(CASE_REFERENCE).build();
  }

  private static UploadResponse uploadResponse(Document document) {
    return new UploadResponse(List.of(document));
  }

  private static Document document(String selfHref, String binaryHref, String hashToken) {
    Document.Links links = new Document.Links();
    links.self = new Document.Link();
    links.self.href = selfHref;
    links.binary = new Document.Link();
    links.binary.href = binaryHref;
    return Document.builder().hashToken(hashToken).links(links).build();
  }

  private static BundleArtifact failingArtifact() {
    return new BundleArtifact() {
      @Override
      public String fileName() {
        return "hearing-bundle.pdf";
      }

      @Override
      public String mediaType() {
        return "application/pdf";
      }

      @Override
      public long size() {
        return PDF_BYTES.length;
      }

      @Override
      public String sha256() {
        return "test-sha256";
      }

      @Override
      public int pageCount() {
        return 1;
      }

      @Override
      public InputStream open() throws IOException {
        throw new IOException("disk gone");
      }
    };
  }
}

package uk.gov.hmcts.ccd.sdk.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import feign.FeignException;
import feign.RetryableException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import uk.gov.hmcts.ccd.sdk.type.Document;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;
import uk.gov.hmcts.reform.ccd.document.am.model.CaseDocumentsMetadata;
import uk.gov.hmcts.reform.ccd.document.am.model.DocumentHashToken;
import uk.gov.hmcts.reform.ccd.document.am.model.DocumentUploadRequest;
import uk.gov.hmcts.reform.ccd.document.am.util.InMemoryMultipartFile;

/** An application's own CDAM client, answered from the documents a test stored. */
@SpringBootTest(
    classes = TestDocumentsIntegrationTest.FeignApplication.class,
    properties = "case_document_am.url=http://cdam.invalid")
class TestDocumentsIntegrationTest {

  @Autowired private TestDocuments documents;
  @Autowired private Cdam cdam;
  @Autowired private CaseDocumentClientApi client;

  @Test
  void describesAStoredDocumentAsCdamDoes() {
    Document stored = documents.store("Order for possession.pdf");

    Map<String, Object> metadata = cdam.metadata(idOf(stored));

    assertThat(metadata)
        .containsEntry("originalDocumentName", "Order for possession.pdf")
        .containsEntry(
            "_links",
            Map.of(
                "self", Map.of("href", stored.getUrl()),
                "binary", Map.of("href", stored.getBinaryUrl())));
  }

  @Test
  void deletingADocumentRemovesItFromTheStore() {
    Document kept = documents.store("kept.pdf");
    Document deleted = documents.store("deleted.pdf");

    cdam.delete(idOf(deleted), true);

    assertThat(documents.stored()).contains(kept).doesNotContain(deleted);
    assertThatThrownBy(() -> cdam.metadata(idOf(deleted)))
        .isInstanceOf(FeignException.NotFound.class);
  }

  @Test
  void storedDocumentsContentIsItsFileName() {
    Document stored = documents.store("Ordér.pdf");

    assertThat(cdam.binary(idOf(stored))).isEqualTo("Ordér.pdf");
    assertThat(cdam.metadata(idOf(stored))).containsEntry("size", 10);
  }

  @Test
  void confirmsTheTimeToLiveAsked() {
    Document stored = documents.store("Order for possession.pdf");

    Map<String, Object> ttl = cdam.patch(idOf(stored), Map.of("ttl", "2026-12-31T00:00:00"));

    assertThat(ttl)
        .containsEntry("ttl", "2026-12-31T00:00:00")
        .containsKeys("createdOn", "modifiedOn");
  }

  @Test
  void otherCallsStillGoToCdam() {
    assertThatThrownBy(() -> cdam.upload(Map.of())).isInstanceOf(RetryableException.class);
  }

  @Test
  void anUnknownDocumentIsNotFound() {
    assertThatThrownBy(() -> cdam.metadata(UUID.randomUUID()))
        .isInstanceOf(FeignException.NotFound.class);
  }

  @Test
  void uploadsRealBytesAndAttachesTheDocumentUsingTheApplicationsCdamClient() throws Exception {
    byte[] content = {(byte) 0xff, 0, 1, 2, '\r', '\n', 3, (byte) 0x80};
    var uploaded =
        client
            .uploadDocuments(
                "Bearer actor",
                "s2s",
                new DocumentUploadRequest(
                    "PUBLIC",
                    "TestCase",
                    "TEST",
                    List.of(
                        new InMemoryMultipartFile(
                            "files", "Ordér.pdf", "application/pdf", content))))
            .getDocuments()
            .getFirst();
    Document stored =
        documents.stored().stream()
            .filter(document -> document.getUrl().equals(uploaded.links.self.href))
            .findFirst()
            .orElseThrow();
    assertThat(stored.getFilename()).isEqualTo("Ordér.pdf");
    assertThat(uploaded.hashToken).isNotBlank();
    assertThat(uploaded.originalDocumentName).isEqualTo("Ordér.pdf");
    assertThat(uploaded.mimeType).isEqualTo("application/pdf");
    assertThat(uploaded.size).isEqualTo(content.length);
    assertThat(documents.content(stored)).containsExactly(content);
    assertThat(documents.caseReference(stored)).isEmpty();
    try (var binary =
        client.getDocumentBinary("Bearer actor", "s2s", idOf(stored)).getBody().getInputStream()) {
      assertThat(binary.readAllBytes()).containsExactly(content);
    }

    attach(stored, 1234567890123456L);
    attach(stored, 1234567890123456L);

    assertThat(documents.caseReference(stored)).hasValue(1234567890123456L);
    assertThatThrownBy(() -> attach(stored, 2222222222222222L))
        .isInstanceOf(FeignException.Conflict.class);
    client.deleteDocument("Bearer actor", "s2s", idOf(stored), true);
    assertThat(documents.stored()).doesNotContain(stored);
  }

  @Test
  void missingAttachmentsDoNotPartiallyAttachTheOtherDocuments() {
    Document stored = documents.store("Present.pdf");
    assertThatThrownBy(
            () ->
                client.patchDocument(
                    "Bearer actor",
                    "s2s",
                    CaseDocumentsMetadata.builder()
                        .caseId("1234567890123456")
                        .caseTypeId("TestCase")
                        .jurisdictionId("TEST")
                        .documentHashTokens(
                            List.of(
                                DocumentHashToken.builder().id(idOf(stored).toString()).build(),
                                DocumentHashToken.builder()
                                    .id(UUID.randomUUID().toString())
                                    .build()))
                        .build()))
        .isInstanceOf(FeignException.NotFound.class);
    assertThat(documents.caseReference(stored)).isEmpty();
  }

  @Test
  void storesActualSourceContentWithoutExposingMutableBytes() {
    byte[] content = "source bytes".getBytes(StandardCharsets.UTF_8);
    Document stored = documents.store("source.txt", "text/plain", content);
    content[0] = 0;
    byte[] read = documents.content(stored);
    read[0] = 0;
    assertThat(documents.content(stored))
        .isEqualTo("source bytes".getBytes(StandardCharsets.UTF_8));
    documents.delete(stored);
    assertThatThrownBy(() -> cdam.binary(idOf(stored))).isInstanceOf(FeignException.NotFound.class);
  }

  @Test
  void uploadsSeveralFilesInOrderWithTheirOwnMediaTypesAndContent() throws Exception {
    byte[] first = {1, 2, (byte) 255};
    byte[] second = "a text file".getBytes(StandardCharsets.UTF_8);
    var uploaded =
        client
            .uploadDocuments(
                "actor",
                "s2s",
                new DocumentUploadRequest(
                    "PUBLIC",
                    "TestCase",
                    "TEST",
                    List.of(
                        new InMemoryMultipartFile("files", "first.png", "image/png", first),
                        new InMemoryMultipartFile("files", "second.txt", "text/plain", second))))
            .getDocuments();
    assertThat(uploaded)
        .extracting(document -> document.originalDocumentName)
        .containsExactly("first.png", "second.txt");
    assertThat(uploaded)
        .extracting(document -> document.mimeType)
        .containsExactly("image/png", "text/plain");
    var binary =
        client.getDocumentBinary(
            "actor",
            "s2s",
            UUID.fromString(
                uploaded
                    .get(1)
                    .links
                    .self
                    .href
                    .substring(uploaded.get(1).links.self.href.lastIndexOf('/') + 1)));
    assertThat(binary.getHeaders().getContentType().toString()).isEqualTo("text/plain");
    try (var stream = binary.getBody().getInputStream()) {
      assertThat(stream.readAllBytes()).isEqualTo(second);
    }
  }

  @Test
  void rejectsMalformedAttachmentRequestsAsBadRequests() {
    for (Map<String, Object> bad :
        List.of(
            Map.<String, Object>of("caseId", "bad", "documentHashTokens", List.of()),
            Map.<String, Object>of(
                "caseId", "1234567890123456", "documentHashTokens", List.of(Map.of("id", "bad"))),
            Map.<String, Object>of("documentHashTokens", List.of()))) {
      assertThatThrownBy(() -> cdam.attach(bad)).isInstanceOf(FeignException.BadRequest.class);
    }
  }

  @Test
  void rejectsMultipartUploadsWithoutAnyFilePart() {
    assertThatThrownBy(
            () ->
                client.uploadDocuments(
                    "actor",
                    "s2s",
                    new DocumentUploadRequest("PUBLIC", "TestCase", "TEST", List.of())))
        .isInstanceOf(FeignException.BadRequest.class);
  }

  @Test
  void attachingADeletedDocumentIsNotFoundAndContentInspectionHasAClearError() {
    Document document = documents.store("removed.pdf");
    documents.delete(document);
    assertThatThrownBy(() -> attach(document, 1234567890123456L))
        .isInstanceOf(FeignException.NotFound.class);
    assertThatThrownBy(() -> documents.content(document))
        .isInstanceOf(java.util.NoSuchElementException.class)
        .hasMessageContaining("No stored document");
  }

  private void attach(Document document, long reference) {
    client.patchDocument(
        "Bearer actor",
        "s2s",
        CaseDocumentsMetadata.builder()
            .caseId(String.valueOf(reference))
            .caseTypeId("TestCase")
            .jurisdictionId("TEST")
            .documentHashTokens(
                List.of(
                    DocumentHashToken.builder()
                        .id(idOf(document).toString())
                        .hashToken(document.getHashToken())
                        .build()))
            .build());
  }

  private static UUID idOf(Document document) {
    return UUID.fromString(document.getUrl().substring(document.getUrl().lastIndexOf('/') + 1));
  }

  /** CDAM's document endpoints, as its Java client calls them. */
  @FeignClient(name = "cdam", url = "http://cdam.invalid/cases/documents")
  interface Cdam {
    @GetMapping("/{documentId}")
    Map<String, Object> metadata(@PathVariable("documentId") UUID documentId);

    @GetMapping("/{documentId}/binary")
    String binary(@PathVariable("documentId") UUID documentId);

    @PatchMapping("/{documentId}")
    Map<String, Object> patch(
        @PathVariable("documentId") UUID documentId, @RequestBody Map<String, Object> ttl);

    @PostMapping
    Map<String, Object> upload(@RequestBody Map<String, Object> documents);

    @PatchMapping("/attachToCase")
    Map<String, Object> attach(@RequestBody Map<String, Object> metadata);

    @DeleteMapping("/{documentId}")
    void delete(
        @PathVariable("documentId") UUID documentId, @RequestParam("permanent") boolean permanent);
  }

  @SpringBootConfiguration
  @ImportAutoConfiguration({
    FeignAutoConfiguration.class,
    HttpMessageConvertersAutoConfiguration.class,
    JacksonAutoConfiguration.class
  })
  @EnableFeignClients(clients = {Cdam.class, CaseDocumentClientApi.class})
  @Import(CcdEventTestConfiguration.class)
  static class FeignApplication {}
}

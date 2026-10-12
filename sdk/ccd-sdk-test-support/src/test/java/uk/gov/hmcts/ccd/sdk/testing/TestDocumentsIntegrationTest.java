package uk.gov.hmcts.ccd.sdk.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import feign.FeignException;
import feign.RetryableException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
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

/** An application's own CDAM client, answered from the documents a test stored. */
@SpringBootTest(classes = TestDocumentsIntegrationTest.FeignApplication.class)
class TestDocumentsIntegrationTest {

  @Autowired
  private TestDocuments documents;
  @Autowired
  private Cdam cdam;

  @Test
  void describesAStoredDocumentAsCdamDoes() {
    Document stored = documents.store("Order for possession.pdf");

    Map<String, Object> metadata = cdam.metadata(idOf(stored));

    assertThat(metadata).containsEntry("originalDocumentName", "Order for possession.pdf")
        .containsEntry("_links", Map.of(
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

    assertThat(ttl).containsEntry("ttl", "2026-12-31T00:00:00").containsKeys("createdOn", "modifiedOn");
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
    Map<String, Object> patch(@PathVariable("documentId") UUID documentId, @RequestBody Map<String, Object> ttl);

    @PostMapping
    Map<String, Object> upload(@RequestBody Map<String, Object> documents);

    @DeleteMapping("/{documentId}")
    void delete(@PathVariable("documentId") UUID documentId, @RequestParam("permanent") boolean permanent);
  }

  @SpringBootConfiguration
  @ImportAutoConfiguration({
      FeignAutoConfiguration.class,
      HttpMessageConvertersAutoConfiguration.class,
      Jackson2AutoConfiguration.class
  })
  @EnableFeignClients(clients = Cdam.class)
  @Import(CcdEventTestConfiguration.class)
  static class FeignApplication {
  }
}

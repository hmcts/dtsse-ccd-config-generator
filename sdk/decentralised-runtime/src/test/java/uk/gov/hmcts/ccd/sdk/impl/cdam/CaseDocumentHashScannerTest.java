package uk.gov.hmcts.ccd.sdk.impl.cdam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CaseDocumentHashScannerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final CaseDocumentHashScanner scanner = new CaseDocumentHashScanner();

  @Test
  void findsNewDocumentHashTokensInNestedData() throws Exception {
    JsonNode existingData = read("""
        {
          "existingDoc": {
            "document_url": "http://dm-store/documents/11111111-1111-1111-1111-111111111111"
          }
        }
        """);
    JsonNode submittedData = read("""
        {
          "existingDoc": {
            "document_url": "http://dm-store/documents/11111111-1111-1111-1111-111111111111"
          },
          "collection": [
            {
              "value": {
                "uploaded": {
                  "document_url": "http://dm-store/documents/22222222-2222-2222-2222-222222222222",
                  "document_hash": "hash-token"
                }
              }
            }
          ]
        }
        """);

    var tokens = scanner.findNewDocumentHashTokens(existingData, submittedData);

    assertThat(tokens).containsExactly(new DocumentHashToken("22222222-2222-2222-2222-222222222222", "hash-token"));
  }

  @Test
  void stripsDocumentHashesFromNestedDataWithoutChangingOriginal() throws Exception {
    JsonNode submittedData = read("""
        {
          "document": {
            "document_url": "http://dm-store/documents/22222222-2222-2222-2222-222222222222",
            "document_hash": "hash-token"
          },
          "collection": [
            {
              "value": {
                "document_hash": "nested-token"
              }
            }
          ]
        }
        """);

    JsonNode stripped = scanner.stripDocumentHashes(submittedData);

    assertThat(stripped.findValues("document_hash")).isEmpty();
    assertThat(submittedData.findValues("document_hash")).hasSize(2);
  }

  @Test
  void failsWhenNewDocumentIsMissingHash() throws Exception {
    JsonNode submittedData = read("""
        {
          "document": {
            "document_url": "http://dm-store/documents/22222222-2222-2222-2222-222222222222"
          }
        }
        """);

    assertThatThrownBy(() -> scanner.findNewDocumentHashTokens(null, submittedData))
        .isInstanceOf(CdamAttachException.class)
        .hasMessageContaining("missing document_hash");
  }

  @Test
  void failsWhenNewDocumentUrlDoesNotContainValidDocumentId() throws Exception {
    JsonNode submittedData = read("""
        {
          "document": {
            "document_url": "http://dm-store/documents/not-a-uuid",
            "document_hash": "hash-token"
          }
        }
        """);

    assertThatThrownBy(() -> scanner.findNewDocumentHashTokens(null, submittedData))
        .isInstanceOf(CdamAttachException.class)
        .hasMessageContaining("valid document id");
  }

  private JsonNode read(String json) throws Exception {
    return MAPPER.readTree(json);
  }
}

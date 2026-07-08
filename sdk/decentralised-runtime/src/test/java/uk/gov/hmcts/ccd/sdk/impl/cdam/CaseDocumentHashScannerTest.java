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
  void ignoresDocumentUrlsAlreadyPresentBeforeCallbackEvenWhenHashIsNotReturned() throws Exception {
    JsonNode preCallbackData = read("""
        {
          "eventInputDocument": {
            "document_url": "http://dm-store/documents/11111111-1111-1111-1111-111111111111",
            "document_hash": "event-input-hash"
          }
        }
        """);
    JsonNode postCallbackData = read("""
        {
          "eventInputDocument": {
            "document_url": "http://dm-store/documents/11111111-1111-1111-1111-111111111111"
          }
        }
        """);

    assertThat(scanner.findNewDocumentHashTokens(preCallbackData, postCallbackData)).isEmpty();
  }

  @Test
  void comparesExistingDocumentsByDocumentIdNotRawUrl() throws Exception {
    JsonNode preCallbackData = read("""
        {
          "eventInputDocument": {
            "document_url": "http://dm-store/documents/11111111-1111-1111-1111-111111111111"
          }
        }
        """);
    JsonNode postCallbackData = read("""
        {
          "movedDocument": {
            "document_url": "https://case-document-am/documents/11111111-1111-1111-1111-111111111111"
          }
        }
        """);

    assertThat(scanner.findNewDocumentHashTokens(preCallbackData, postCallbackData)).isEmpty();
  }

  @Test
  void treatsDocumentFromCaseDetailsBeforeAsExistingWhenMovedToAnotherField() throws Exception {
    JsonNode preCallbackData = read("""
        [
          {
            "storedDocument": {
              "document_url": "http://dm-store/documents/11111111-1111-1111-1111-111111111111"
            }
          },
          {
            "note": "event input"
          }
        ]
        """);
    JsonNode postCallbackData = read("""
        {
          "movedDocument": {
            "document_url": "http://dm-store/documents/11111111-1111-1111-1111-111111111111"
          }
        }
        """);

    assertThat(scanner.findNewDocumentHashTokens(preCallbackData, postCallbackData)).isEmpty();
  }

  @Test
  void failsWhenExistingDocumentReturnsHashToken() throws Exception {
    JsonNode preCallbackData = read("""
        {
          "storedDocument": {
            "document_url": "http://dm-store/documents/11111111-1111-1111-1111-111111111111"
          }
        }
        """);
    JsonNode postCallbackData = read("""
        {
          "storedDocument": {
            "document_url": "http://dm-store/documents/11111111-1111-1111-1111-111111111111",
            "document_hash": "tampered-hash"
          }
        }
        """);

    assertThatThrownBy(() -> scanner.findNewDocumentHashTokens(preCallbackData, postCallbackData))
        .isInstanceOf(CdamAttachException.class)
        .hasMessageContaining("must not return document_hash");
  }

  @Test
  void stripsDocumentHashesOnlyFromDocumentNodesWithoutChangingOriginal() throws Exception {
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

    assertThat(stripped.at("/document").has("document_hash")).isFalse();
    assertThat(stripped.at("/collection/0/value/document_hash").asText()).isEqualTo("nested-token");
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

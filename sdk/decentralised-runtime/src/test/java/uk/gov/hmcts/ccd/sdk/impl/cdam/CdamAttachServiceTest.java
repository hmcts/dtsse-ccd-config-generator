package uk.gov.hmcts.ccd.sdk.impl.cdam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedEventDetails;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;

class CdamAttachServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final CaseDocumentAmClient client = mock(CaseDocumentAmClient.class);
  private final CdamAttachService service = new CdamAttachService(new CaseDocumentHashScanner(), client);

  @AfterEach
  void resetRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void attachesOnlyDocumentsAddedByAboutToSubmitCallback() throws Exception {
    withAuthorisation("Bearer user-token");
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
          },
          "callbackDocument": {
            "document_url": "http://dm-store/documents/22222222-2222-2222-2222-222222222222",
            "document_hash": "callback-hash"
          }
        }
        """);

    JsonNode stripped = service.attachNewDocumentsAndStripHashes(
        event(),
        preCallbackData,
        postCallbackData
    );

    assertThat(stripped.findValues("document_hash")).isEmpty();

    ArgumentCaptor<CaseDocumentsMetadata> metadata = ArgumentCaptor.forClass(CaseDocumentsMetadata.class);
    verify(client).attach(org.mockito.ArgumentMatchers.eq("Bearer user-token"), metadata.capture());

    assertThat(metadata.getValue().documentHashTokens())
        .containsExactly(new DocumentHashToken("22222222-2222-2222-2222-222222222222", "callback-hash"));
  }

  @Test
  void doesNotAttachDocumentsThatCameFromEventInput() throws Exception {
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

    JsonNode stripped = service.attachNewDocumentsAndStripHashes(
        event(),
        preCallbackData,
        postCallbackData
    );

    assertThat(stripped.findValues("document_hash")).isEmpty();
    verify(client, never()).attach(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void failsWhenNewDocumentsNeedAttachButAuthorisationHeaderIsMissing() throws Exception {
    JsonNode postCallbackData = read("""
        {
          "callbackDocument": {
            "document_url": "http://dm-store/documents/22222222-2222-2222-2222-222222222222",
            "document_hash": "callback-hash"
          }
        }
        """);

    assertThatThrownBy(() -> service.attachNewDocumentsAndStripHashes(event(), null, postCallbackData))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Authorization header is required");

    verify(client, never()).attach(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  private DecentralisedCaseEvent event() {
    CaseDetails caseDetails = new CaseDetails();
    caseDetails.setReference(1234567890123456L);
    caseDetails.setJurisdiction("TEST");

    return DecentralisedCaseEvent.builder()
        .caseDetails(caseDetails)
        .eventDetails(DecentralisedEventDetails.builder()
            .caseType("TestCase")
            .eventId("submit")
            .build())
        .build();
  }

  private JsonNode read(String json) throws Exception {
    return MAPPER.readTree(json);
  }

  private void withAuthorisation(String authorisation) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeaders.AUTHORIZATION, authorisation);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }
}

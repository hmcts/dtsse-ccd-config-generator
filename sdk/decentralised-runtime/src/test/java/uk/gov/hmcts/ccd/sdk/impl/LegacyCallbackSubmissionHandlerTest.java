package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedEventDetails;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToSubmit;
import uk.gov.hmcts.ccd.sdk.impl.cdam.CdamAttachService;
import uk.gov.hmcts.ccd.sdk.runtime.CcdCallbackExecutor;
import uk.gov.hmcts.ccd.sdk.type.Document;

class LegacyCallbackSubmissionHandlerTest {

  private static final TypeReference<Map<String, JsonNode>> JSON_NODE_MAP = new TypeReference<>() {};
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String AUTHORISATION = "Bearer test-token";

  private final ResolvedConfigRegistry registry = mock(ResolvedConfigRegistry.class);
  private final CcdCallbackExecutor executor = mock(CcdCallbackExecutor.class);
  private final ObjectProvider<CdamAttachService> cdamAttachServiceProvider = mock(ObjectProvider.class);
  private final CdamAttachService cdamAttachService = mock(CdamAttachService.class);
  private final LegacyCallbackSubmissionHandler handler =
      new LegacyCallbackSubmissionHandler(registry, executor, MAPPER, cdamAttachServiceProvider);

  @Test
  void leavesCallbackDataUnchangedWhenCdamAttachServiceIsDisabled() throws Exception {
    setupEventConfig();
    DecentralisedCaseEvent event = event();
    when(executor.aboutToSubmit(any())).thenReturn(callbackResponse("""
        {
          "generatedDocument": {
            "document_url": "http://dm-store/documents/22222222-2222-2222-2222-222222222222",
            "document_hash": "hash-token"
          }
        }
        """, List.of()));

    var result = handler.apply(event, AUTHORISATION);

    assertThat(event.getCaseDetails().getData().get("generatedDocument").get("document_hash").asText())
        .isEqualTo("hash-token");
    assertThat(result.dataUpdate()).isPresent();
    assertThat(result.dataUpdate().orElseThrow().findValues("document_hash")).hasSize(1);
    verify(cdamAttachService, never()).attachNewDocumentsAndStripHashes(any(), any(), any(), any());
  }

  @Test
  void stripsExternalDocumentHashFromTypedPersistenceSnapshotWhenCdamAttachServiceIsDisabled() throws Exception {
    setupEventConfig(DocumentCaseData.class);
    DecentralisedCaseEvent event = event();
    when(executor.aboutToSubmit(any())).thenReturn(callbackResponse("""
        {
          "generatedDocument": {
            "document_url": "http://dm-store/documents/22222222-2222-2222-2222-222222222222",
            "document_hash": "hash-token"
          }
        }
        """, List.of()));

    var result = handler.apply(event, AUTHORISATION);

    assertThat(event.getCaseDetails().getData().get("generatedDocument").get("document_hash").asText())
        .isEqualTo("hash-token");
    assertThat(result.dataUpdate()).isPresent();
    assertThat(result.dataUpdate().orElseThrow().findValues("document_hash")).isEmpty();
    verify(cdamAttachService, never()).attachNewDocumentsAndStripHashes(any(), any(), any(), any());
  }

  @Test
  void attachesCdamDocumentsAndStripsHashesBeforeSnapshot() throws Exception {
    setupEventConfig();
    DecentralisedCaseEvent event = event();
    JsonNode strippedData = read("""
        {
          "generatedDocument": {
            "document_url": "http://dm-store/documents/22222222-2222-2222-2222-222222222222"
          }
        }
        """);
    when(cdamAttachServiceProvider.getIfAvailable()).thenReturn(cdamAttachService);
    when(cdamAttachService.attachNewDocumentsAndStripHashes(eq(AUTHORISATION), eq(event), any(), any()))
        .thenReturn(strippedData);
    when(executor.aboutToSubmit(any())).thenReturn(callbackResponse("""
        {
          "generatedDocument": {
            "document_url": "http://dm-store/documents/22222222-2222-2222-2222-222222222222",
            "document_hash": "hash-token"
          }
        }
        """, List.of()));

    var result = handler.apply(event, AUTHORISATION);

    assertThat(event.getCaseDetails().getData().get("generatedDocument").has("document_hash")).isFalse();
    assertThat(result.dataUpdate()).isPresent();
    assertThat(result.dataUpdate().orElseThrow().findValues("document_hash")).isEmpty();

    ArgumentCaptor<JsonNode> preCallbackData = ArgumentCaptor.forClass(JsonNode.class);
    ArgumentCaptor<JsonNode> postCallbackData = ArgumentCaptor.forClass(JsonNode.class);
    verify(cdamAttachService).attachNewDocumentsAndStripHashes(
        eq(AUTHORISATION),
        eq(event),
        preCallbackData.capture(),
        postCallbackData.capture()
    );
    assertThat(preCallbackData.getValue().findValues("document_hash")).isEmpty();
    assertThat(postCallbackData.getValue().findValues("document_hash")).hasSize(1);
  }

  @Test
  void doesNotAttachWhenAboutToSubmitReturnsValidationErrors() throws Exception {
    setupEventConfig();
    DecentralisedCaseEvent event = event();
    when(cdamAttachServiceProvider.getIfAvailable()).thenReturn(cdamAttachService);
    when(executor.aboutToSubmit(any())).thenReturn(callbackResponse("""
        {
          "generatedDocument": {
            "document_url": "http://dm-store/documents/22222222-2222-2222-2222-222222222222",
            "document_hash": "hash-token"
          }
        }
        """, List.of("callback error")));

    assertThatThrownBy(() -> handler.apply(event, AUTHORISATION))
        .isInstanceOf(CallbackValidationException.class);

    verify(cdamAttachService, never()).attachNewDocumentsAndStripHashes(any(), any(), any(), any());
  }

  private void setupEventConfig() {
    setupEventConfig(Map.class);
  }

  private void setupEventConfig(Class<?> caseClass) {
    Event<?, ?, ?> eventConfig = mock(Event.class);
    when(eventConfig.getAboutToSubmitCallback()).thenReturn(mock(AboutToSubmit.class));
    when(eventConfig.getSubmittedCallback()).thenReturn(null);
    doReturn(eventConfig).when(registry).getRequiredEvent("TestCase", "submit");

    ResolvedCCDConfig<?, ?, ?> config = mock(ResolvedCCDConfig.class);
    when(config.getCaseClass()).thenReturn((Class) caseClass);
    doReturn(config).when(registry).getRequired("TestCase");
  }

  private DecentralisedCaseEvent event() {
    CaseDetails caseDetails = new CaseDetails();
    caseDetails.setReference(1234567890123456L);
    caseDetails.setJurisdiction("TEST");
    caseDetails.setCaseTypeId("TestCase");
    caseDetails.setState("Submitted");
    caseDetails.setSecurityClassification(SecurityClassification.PUBLIC);
    caseDetails.setData(Map.of());

    CaseDetails caseDetailsBefore = new CaseDetails();
    caseDetailsBefore.setData(Map.of());

    return DecentralisedCaseEvent.builder()
        .caseDetails(caseDetails)
        .caseDetailsBefore(caseDetailsBefore)
        .eventDetails(DecentralisedEventDetails.builder()
            .caseType("TestCase")
            .eventId("submit")
            .build())
        .build();
  }

  private AboutToStartOrSubmitResponse<Map<String, JsonNode>, Object> callbackResponse(String dataJson,
                                                                                       List<String> errors)
      throws Exception {
    return AboutToStartOrSubmitResponse.<Map<String, JsonNode>, Object>builder()
        .data(MAPPER.convertValue(read(dataJson), JSON_NODE_MAP))
        .errors(errors)
        .build();
  }

  private JsonNode read(String json) throws Exception {
    return MAPPER.readTree(json);
  }

  static class DocumentCaseData {
    public Document generatedDocument;
  }
}

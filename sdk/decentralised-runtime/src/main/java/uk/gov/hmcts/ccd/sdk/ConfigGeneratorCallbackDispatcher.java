package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.MultiValueMap;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.Webhook;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.ccd.sdk.runtime.CallbackController;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

@Slf4j
@RequiredArgsConstructor
@Configuration
public class ConfigGeneratorCallbackDispatcher implements CCDEventListener {

  private final CallbackController controller;
  private final ResolvedConfigRegistry registry;
  private final ObjectMapper mapper;

  @SneakyThrows
  @Override
  public SubmitResponse submit(String caseType, String event, DecentralisedCaseEvent e,
                               MultiValueMap<String, String> urlParams) {
    var config = registry.getRequired(caseType);
    Map<String, Object> rawData = mapper.convertValue(
        e.getCaseDetails().getData(),
        new TypeReference<Map<String, Object>>() {}
    );
    if (rawData == null) {
      rawData = Map.of();
    }
    Map<String, Object> migratedData = registry.applyPreEventHooks(caseType, rawData);
    Map<String, JsonNode> normalisedData = mapper.convertValue(
        migratedData,
        new TypeReference<Map<String, JsonNode>>() {}
    );
    e.getCaseDetails().setData(normalisedData);
    String json = mapper.writeValueAsString(migratedData);
    var domainClass = mapper.readValue(json, config.getCaseClass());

    long caseRef = e.getCaseDetails().getReference();
    EventPayload payload = new EventPayload<>(caseRef, domainClass, urlParams);
    var handler = config.getEvents().get(event).getSubmitHandler();
    return handler.submit(payload);
  }

  @SneakyThrows
  @Override
  public String nameForState(String caseType, String stateId) {
    return registry.labelForState(caseType, stateId).orElse(stateId);
  }

  @Override
  public AboutToStartOrSubmitResponse aboutToSubmit(CallbackRequest request) {
    return controller.aboutToSubmit(request);
  }

  @Override
  public SubmittedCallbackResponse submitted(CallbackRequest request) {
    var resolved = registry.getRequired(request.getCaseDetails().getCaseTypeId());
    var event = resolved.getEvents().get(request.getEventId());
    var r = event.getRetries().get(Webhook.Submitted);
    int retries = r == null || r.isEmpty() ? 1 : 3;
    for (int i = 0; i < retries; i++) {
      try {
        var submitted = controller.submitted(request);
        log.debug("Generator submitted callback returned header={} body={}",
            submitted != null ? submitted.getConfirmationHeader() : null,
            submitted != null ? submitted.getConfirmationBody() : null);
        return submitted;
      } catch (Exception e) {
        log.warn("Unsuccessful submitted callback {}", e);
      }
    }
    // TODO: populate failure
    return SubmittedCallbackResponse.builder().build();
  }
}

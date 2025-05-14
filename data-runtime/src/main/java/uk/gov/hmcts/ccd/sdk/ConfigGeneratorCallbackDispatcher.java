package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.MultiValueMap;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.callback.Submit;
import uk.gov.hmcts.ccd.sdk.runtime.CallbackController;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

@RequiredArgsConstructor
@Configuration
public class ConfigGeneratorCallbackDispatcher implements CCDEventListener {

  private final CallbackController controller;
  private final ObjectMapper mapper;

  public boolean hasAboutToSubmitCallbackForEvent(String caseType, String event) {
    return controller.hasAboutToSubmitCallback(caseType, event);
  }

  @SneakyThrows
  @Override
  public void submit(String caseType, String event, POCCaseEvent e, MultiValueMap<String, String> urlParams) {
    var ct = controller.getCaseTypeToConfig().get(caseType);
    String json = mapper.writeValueAsString(e.getCaseDetails().get("case_data"));
    var domainClass = mapper.readValue(json, ct.getCaseClass());

    long caseRef = (long) e.getCaseDetails().get("id");
    EventPayload payload = new EventPayload<>(caseRef, domainClass, urlParams);
    var handler = ct.getEvents().get(event).getSubmitHandler();
    handler.submit(payload);
  }

  @Override
  public boolean hasSubmitHandler(String caseType, String event) {
    var t = controller.getCaseTypeToConfig().get(caseType).getEvents().get(event).getSubmitHandler();
    return t != null;
  }

  @SneakyThrows
  @Override
  public String nameForState(String caseType, String stateId) {
    // TODO: Refactor the config generator to reuse label extraction
    var resolved = controller.getCaseTypeToConfig().get(caseType);
    var clazz = resolved.getStateClass();
    var enumType =
        Arrays.stream(clazz.getEnumConstants()).filter(x -> x.toString().equals(stateId)).findFirst().orElseThrow();
    CCD ccd = clazz.getField(enumType.toString()).getAnnotation(CCD.class);
    return ccd != null && !Strings.isNullOrEmpty(ccd.label()) ? ccd.label() :
        enumType.toString();
  }

  @Override
  public AboutToStartOrSubmitResponse aboutToSubmit(CallbackRequest request) {
    return controller.aboutToSubmit(request);
  }

  @Override
  public boolean hasSubmittedCallbackForEvent(String caseType, String event) {
    return controller.hasSubmittedCallback(caseType, event);
  }
}

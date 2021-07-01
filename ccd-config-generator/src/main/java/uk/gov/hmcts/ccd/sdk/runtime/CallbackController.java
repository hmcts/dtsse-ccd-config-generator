package uk.gov.hmcts.ccd.sdk.runtime;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.util.Collection;
import java.util.Map;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.callback.MidEvent;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

@Slf4j
@RestController
@RequestMapping("/callbacks")
public class CallbackController {

  private final ImmutableMap<String, ResolvedCCDConfig<?, ?, ?>> caseTypeToConfig;

  private ObjectMapper mapper;

  private final Map<String, JavaType> caseTypeToJavaType = Maps.newHashMap();

  @Autowired
  public CallbackController(Collection<ResolvedCCDConfig<?, ?, ?>> configs, ObjectMapper mapper) {
    this.caseTypeToConfig = Maps.uniqueIndex(configs, x -> x.caseType);
    this.mapper = mapper;
    for (ResolvedCCDConfig<?, ?, ?> config : configs) {
      this.caseTypeToJavaType.put(config.caseType,
          mapper.getTypeFactory().constructParametricType(CaseDetails.class, config.typeArg, config.stateArg));
    }
  }

  @SneakyThrows
  @PostMapping("/about-to-start")
  public AboutToStartOrSubmitResponse aboutToStart(@RequestBody CallbackRequest request) {
    log.info("About to start event ID: " + request.getEventId());
    return findCallback(findCaseType(request).aboutToStartCallbacks, request.getEventId())
        .handle(convertCaseDetails(request.getCaseDetails()));
  }

  @SneakyThrows
  @PostMapping("/about-to-submit")
  public AboutToStartOrSubmitResponse aboutToSubmit(@RequestBody CallbackRequest request) {
    log.info("About to submit event ID: " + request.getEventId());
    return findCallback(findCaseType(request).aboutToSubmitCallbacks, request.getEventId())
        .handle(convertCaseDetails(request.getCaseDetails()),
            convertCaseDetails(request.getCaseDetailsBefore(), request.getCaseDetails().getCaseTypeId()));
  }

  @SneakyThrows
  @PostMapping("/submitted")
  public SubmittedCallbackResponse submitted(@RequestBody CallbackRequest request) {
    log.info("Submitted event ID: " + request.getEventId());
    return findCallback(findCaseType(request).submittedCallbacks, request.getEventId())
        .handle(convertCaseDetails(request.getCaseDetails()), convertCaseDetails(request.getCaseDetailsBefore(),
            request.getCaseDetails().getCaseTypeId()));
  }

  @SneakyThrows
  @PostMapping("/mid-event")
  public AboutToStartOrSubmitResponse midEvent(@RequestBody CallbackRequest request,
                                            @RequestParam(name = "page") String page) {
    log.info("Mid event callback: {} for page {} ", request.getEventId(), page);
    MidEvent m = findCaseType(request).midEventCallbacks.get(request.getEventId(), page);
    if (null == m) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Handler not found for "
          + request.getEventId() + " for page " + page);
    }
    return m.handle(convertCaseDetails(request.getCaseDetails()),
        convertCaseDetails(request.getCaseDetailsBefore(),
            request.getCaseDetails().getCaseTypeId()));
  }

  ResolvedCCDConfig<?, ?, ?> findCaseType(CallbackRequest request) {
    String caseType = request.getCaseDetails().getCaseTypeId();
    if (!caseTypeToConfig.containsKey(caseType)) {
      log.warn("No configuration found for case type " + caseType);
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case type not found: " + caseType);
    }

    return this.caseTypeToConfig.get(caseType);
  }

  <T> T findCallback(Map<String, T> callbacks, String eventId) {
    if (!callbacks.containsKey(eventId)) {
      log.warn("Handler not found for " + eventId);
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Handler not found for " + eventId);
    }
    return callbacks.get(eventId);
  }

  CaseDetails convertCaseDetails(uk.gov.hmcts.reform.ccd.client.model.CaseDetails ccdDetails) {
    return convertCaseDetails(ccdDetails, ccdDetails.getCaseTypeId());
  }

  // Allow the case type to be specified seperately since we will need to
  // deserialize null values to an instance of the correct class.
  @SneakyThrows
  CaseDetails convertCaseDetails(uk.gov.hmcts.reform.ccd.client.model.CaseDetails ccdDetails,
                                 String caseType) {
    String json = mapper.writeValueAsString(ccdDetails);
    if (!caseTypeToJavaType.containsKey(caseType)) {
      log.warn("Handler not found for " + caseType);
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Handler not found for " + caseType);
    }
    return mapper.readValue(json, caseTypeToJavaType.get(caseType));
  }
}

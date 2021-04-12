package uk.gov.hmcts.ccd.sdk.runtime;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

@Slf4j
@RestController
@RequestMapping("/callbacks")
public class CallbackController {

  @Autowired
  private ResolvedCCDConfig<?, ?, ?> ccdConfig;

  @Autowired
  private ObjectMapper mapper;

  private final JavaType caseDetailsType;

  @Autowired
  public CallbackController(ResolvedCCDConfig<?, ?, ?> ccdConfig, ObjectMapper mapper) {
    this.ccdConfig = ccdConfig;
    this.mapper = mapper;
    this.caseDetailsType = mapper.getTypeFactory()
        .constructParametricType(CaseDetails.class, ccdConfig.typeArg, ccdConfig.stateArg);
  }

  @SneakyThrows
  @PostMapping("/about-to-start")
  public AboutToStartOrSubmitResponse aboutToStart(@RequestBody CallbackRequest request) {
    log.info("About to start event ID: " + request.getEventId());
    return findCallback(ccdConfig.aboutToStartCallbacks, request.getEventId())
        .handle(convertCaseDetails(request.getCaseDetails()));
  }

  @SneakyThrows
  @PostMapping("/about-to-submit")
  public AboutToStartOrSubmitResponse aboutToSubmit(@RequestBody CallbackRequest request) {
    log.info("About to submit event ID: " + request.getEventId());
    return findCallback(ccdConfig.aboutToSubmitCallbacks, request.getEventId())
        .handle(convertCaseDetails(request.getCaseDetails()), convertCaseDetails(request.getCaseDetailsBefore()));
  }

  @SneakyThrows
  @PostMapping("/submitted")
  public SubmittedCallbackResponse submitted(@RequestBody CallbackRequest request) {
    log.info("Submitted event ID: " + request.getEventId());
    return findCallback(ccdConfig.submittedCallbacks, request.getEventId())
        .handle(convertCaseDetails(request.getCaseDetails()), convertCaseDetails(request.getCaseDetailsBefore()));
  }

  <T> T findCallback(Map<String, T> callbacks, String eventId) {
    if (!callbacks.containsKey(eventId)) {
      log.warn("Handler not found for " + eventId);
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Handler not found for " + eventId);
    }
    return callbacks.get(eventId);
  }

  @SneakyThrows
  CaseDetails convertCaseDetails(uk.gov.hmcts.reform.ccd.client.model.CaseDetails ccdDetails) {
    String json = mapper.writeValueAsString(ccdDetails);
    return mapper.readValue(json, caseDetailsType);
  }
}

package uk.gov.hmcts.ccd.sdk.runtime;

import static java.util.function.Function.identity;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import de.cronn.reflection.util.TypedPropertyGetter;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;

import lombok.Getter;
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
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.callback.MidEvent;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

@Slf4j
@RestController
@RequestMapping("/callbacks")
public class CallbackController {

  @Getter
  private final ImmutableMap<String, ResolvedCCDConfig<?, ?, ?>> caseTypeToConfig;

  private ObjectMapper mapper;

  private final Map<String, JavaType> caseTypeToJavaType = Maps.newHashMap();

  @Autowired
  public CallbackController(Collection<ResolvedCCDConfig<?, ?, ?>> configs, ObjectMapper mapper) {
    this.caseTypeToConfig = Maps.uniqueIndex(configs, ResolvedCCDConfig::getCaseType);
    this.mapper = mapper;
    for (ResolvedCCDConfig<?, ?, ?> config : configs) {
      this.caseTypeToJavaType.put(config.getCaseType(),
          mapper.getTypeFactory().constructParametricType(CaseDetails.class, config.getCaseClass(),
              config.getStateClass()));
    }
  }

  @SneakyThrows
  @PostMapping("/about-to-start")
  public AboutToStartOrSubmitResponse aboutToStart(@RequestBody CallbackRequest request) {
    log.info("About to start event ID: " + request.getEventId());
    return findCallback(request, Event::getAboutToStartCallback)
        .handle(convertCaseDetails(request.getCaseDetails()));
  }

  @SneakyThrows
  @PostMapping("/about-to-submit")
  public AboutToStartOrSubmitResponse aboutToSubmit(@RequestBody CallbackRequest request) {
    log.info("About to submit event ID: " + request.getEventId());
    return findCallback(request, Event::getAboutToSubmitCallback)
        .handle(convertCaseDetails(request.getCaseDetails()),
            convertCaseDetails(request.getCaseDetailsBefore(), request.getCaseDetails().getCaseTypeId()));
  }

  @SneakyThrows
  @PostMapping("/submitted")
  public SubmittedCallbackResponse submitted(@RequestBody CallbackRequest request) {
    log.info("Submitted event ID: " + request.getEventId());
    return findCallback(request, Event::getSubmittedCallback)
        .handle(convertCaseDetails(request.getCaseDetails()), convertCaseDetails(request.getCaseDetailsBefore(),
            request.getCaseDetails().getCaseTypeId()));
  }

  @SneakyThrows
  @PostMapping("/mid-event")
  public AboutToStartOrSubmitResponse midEvent(@RequestBody CallbackRequest request,
                                            @RequestParam(name = "page") String page) {
    log.info("Mid event callback: {} for page {} ", request.getEventId(), page);
    MidEvent<?, ?> callback = findCaseEvent(request).getFields().getPagesToMidEvent().get(page);

    if (null == callback) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Handler not found for "
          + request.getEventId() + " for page " + page);
    }
    return callback.handle(convertCaseDetails(request.getCaseDetails()),
        convertCaseDetails(request.getCaseDetailsBefore(),
            request.getCaseDetails().getCaseTypeId()));
  }

  <T> T findCallback(CallbackRequest request, TypedPropertyGetter<Event<?, ?, ?>, T> getter) {
    T result = getter.get(findCaseEvent(request));
    if (result == null) {
      log.warn("No callback for event " + request.getEventId());
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Callback not found: " + request.getEventId());
    }
    return result;
  }

  <T> Event<?, ?, ?> findCaseEvent(CallbackRequest request) {
    String caseType = request.getCaseDetails().getCaseTypeId();
    if (!caseTypeToConfig.containsKey(caseType)) {
      log.warn("No configuration found for case type " + caseType);
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case type not found: " + caseType);
    }

    Event<?, ?, ?> result = caseTypeToConfig.get(caseType).getEvents().get(request.getEventId());
    if (result == null) {
      log.warn("Unknown event " + request.getEventId());
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case event not found: " + request.getEventId());
    }

    return result;
  }

  CaseDetails convertCaseDetails(uk.gov.hmcts.reform.ccd.client.model.CaseDetails ccdDetails) {
    return convertCaseDetails(ccdDetails, ccdDetails.getCaseTypeId());
  }

  // Allow the case type to be specified seperately since we will need to
  // deserialize null values to an instance of the correct class.
  @SneakyThrows
  CaseDetails convertCaseDetails(uk.gov.hmcts.reform.ccd.client.model.CaseDetails ccdDetails,
                                 String caseType) {

    if (!caseTypeToJavaType.containsKey(caseType)) {
      log.warn("Handler not found for " + caseType);
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Handler not found for " + caseType);
    }

    if (null != ccdDetails) {
      try {
        Map<String, Object> migratedData = caseTypeToConfig.get(caseType).getPreEventHooks()
            .stream()
            .reduce(identity(), Function::andThen)
            .apply(ccdDetails.getData());

        ccdDetails.setData(migratedData);
      } catch (Exception e) {
        log.error("Error running pre-event hooks", e);
      }
    }

    String json = mapper.writeValueAsString(ccdDetails);
    return mapper.readValue(json, caseTypeToJavaType.get(caseType));
  }

  public boolean hasAboutToSubmitCallback(String caseType, String event) {
    if (!caseTypeToConfig.containsKey(caseType)) {
      return false;
    }

    Event<?, ?, ?> result = caseTypeToConfig.get(caseType).getEvents().get(event);
    if (result == null) {
      return false;
    }

    return result.getAboutToSubmitCallback() != null;
  }

  public boolean hasSubmittedCallback(String caseType, String event) {
    if (!caseTypeToConfig.containsKey(caseType)) {
      return false;
    }

    Event<?, ?, ?> result = caseTypeToConfig.get(caseType).getEvents().get(event);
    if (result == null) {
      return false;
    }

    return result.getSubmittedCallback() != null;
  }
}

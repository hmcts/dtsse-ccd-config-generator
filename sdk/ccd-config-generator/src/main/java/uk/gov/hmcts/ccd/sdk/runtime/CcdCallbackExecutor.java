package uk.gov.hmcts.ccd.sdk.runtime;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.TypedPropertyGetter;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.callback.MidEvent;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

@Slf4j
@Component
public class CcdCallbackExecutor {

  private final ResolvedConfigRegistry registry;
  private final ObjectMapper mapper;
  private final Map<String, JavaType> caseTypeToJavaType = Maps.newHashMap();

  @Autowired
  public CcdCallbackExecutor(ResolvedConfigRegistry registry, ObjectMapper mapper) {
    this.registry = registry;
    this.mapper = mapper;
    for (ResolvedCCDConfig<?, ?, ?> config : registry.getAll()) {
      this.caseTypeToJavaType.put(config.getCaseType(),
          mapper.getTypeFactory().constructParametricType(CaseDetails.class, config.getCaseClass(),
              config.getStateClass()));
    }
  }

  @SneakyThrows
  @SuppressWarnings("unchecked")
  public AboutToStartOrSubmitResponse aboutToStart(CallbackRequest request) {
    log.info("About to start event ID: {}", request.getEventId());

    var event = findCaseEvent(request);

    if (event.getStartHandler() != null) {
      if (event.isDtoEvent()) {
        Map<String, Object> ccdData = request.getCaseDetails().getData();
        Object dtoData = DtoMapper.fromCcdData(
            ccdData, event.getDtoPrefix(), event.getDtoClass(), mapper);
        EventPayload payload = new EventPayload<>(
            request.getCaseDetails().getId(),
            dtoData,
            new LinkedMultiValueMap<>()
        );
        var response = event.getStartHandler().start(payload);
        Map<String, Object> responseData = new LinkedHashMap<>(ccdData);
        responseData.putAll(DtoMapper.toCcdData(response, event.getDtoPrefix(), mapper));
        return AboutToStartOrSubmitResponse.builder().data(responseData).build();
      }

      var config = registry.getRequired(request.getCaseDetails().getCaseTypeId());
      String json = mapper.writeValueAsString(request.getCaseDetails().getData());
      var domainClass = mapper.readValue(json, config.getCaseClass());
      EventPayload payload = new EventPayload<>(
          request.getCaseDetails().getId(),
          domainClass,
          new LinkedMultiValueMap<>()
      );

      var response = event.getStartHandler().start(payload);
      return AboutToStartOrSubmitResponse.builder().data(response).build();
    }

    return findCallback(request, Event::getAboutToStartCallback)
        .handle(convertCaseDetails(request.getCaseDetails()));
  }

  @SneakyThrows
  public AboutToStartOrSubmitResponse aboutToSubmit(CallbackRequest request) {
    log.info("About to submit event ID: {}", request.getEventId());

    return findCallback(request, Event::getAboutToSubmitCallback)
        .handle(convertCaseDetails(request.getCaseDetails()),
            convertCaseDetails(request.getCaseDetailsBefore(), request.getCaseDetails().getCaseTypeId()));
  }

  @SneakyThrows
  public SubmittedCallbackResponse submitted(CallbackRequest request) {
    log.info("Submitted event ID: {}", request.getEventId());
    return findCallback(request, Event::getSubmittedCallback)
        .handle(convertCaseDetails(request.getCaseDetails()),
            convertCaseDetails(request.getCaseDetailsBefore(), request.getCaseDetails().getCaseTypeId()));
  }

  @SneakyThrows
  @SuppressWarnings("unchecked")
  public AboutToStartOrSubmitResponse midEvent(CallbackRequest request, String page) {
    log.info("Mid event callback: {} for page {}", request.getEventId(), page);
    var event = findCaseEvent(request);
    MidEvent<?, ?> callback = event.getFields().getPagesToMidEvent().get(page);

    if (callback == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Handler not found for "
          + request.getEventId() + " for page " + page);
    }

    if (event.isDtoEvent()) {
      Map<String, Object> ccdData = request.getCaseDetails().getData();
      CaseDetails dtoCaseDetails = buildDtoCaseDetails(
          request.getCaseDetails(), event.getDtoPrefix(), event.getDtoClass());
      CaseDetails dtoCaseDetailsBefore = request.getCaseDetailsBefore() != null
          ? buildDtoCaseDetails(request.getCaseDetailsBefore(), event.getDtoPrefix(), event.getDtoClass())
          : dtoCaseDetails;

      var response = callback.handle(dtoCaseDetails, dtoCaseDetailsBefore);

      if (response.getData() != null) {
        Map<String, Object> responseData = new LinkedHashMap<>(ccdData);
        responseData.putAll(DtoMapper.toCcdData(response.getData(), event.getDtoPrefix(), mapper));
        response.setData(responseData);
      }
      return response;
    }

    return callback.handle(convertCaseDetails(request.getCaseDetails()),
        convertCaseDetails(request.getCaseDetailsBefore(),
            request.getCaseDetails().getCaseTypeId()));
  }

  private <T> T findCallback(CallbackRequest request, TypedPropertyGetter<Event<?, ?, ?>, T> getter) {
    T result = getter.get(findCaseEvent(request));
    if (result == null) {
      log.warn("No callback for event {}", request.getEventId());
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Callback not found: " + request.getEventId());
    }
    return result;
  }

  private Event<?, ?, ?> findCaseEvent(CallbackRequest request) {
    String caseType = request.getCaseDetails().getCaseTypeId();
    var config = registry.find(caseType)
        .orElseThrow(() -> {
          log.warn("No configuration found for case type {}", caseType);
          return new ResponseStatusException(HttpStatus.NOT_FOUND, "Case type not found: " + caseType);
        });

    Event<?, ?, ?> result = config.getEvents().get(request.getEventId());
    if (result == null) {
      log.warn("Unknown event {}", request.getEventId());
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case event not found: " + request.getEventId());
    }

    return result;
  }

  @SuppressWarnings("unchecked")
  private CaseDetails buildDtoCaseDetails(
      uk.gov.hmcts.reform.ccd.client.model.CaseDetails ccdDetails, String prefix, Class<?> dtoClass) {
    Object dtoData = DtoMapper.fromCcdData(ccdDetails.getData(), prefix, dtoClass, mapper);
    return CaseDetails.builder()
        .id(ccdDetails.getId())
        .caseTypeId(ccdDetails.getCaseTypeId())
        .data(dtoData)
        .state(ccdDetails.getState())
        .build();
  }

  CaseDetails convertCaseDetails(uk.gov.hmcts.reform.ccd.client.model.CaseDetails ccdDetails) {
    return convertCaseDetails(ccdDetails, ccdDetails.getCaseTypeId());
  }

  @SneakyThrows
  CaseDetails convertCaseDetails(uk.gov.hmcts.reform.ccd.client.model.CaseDetails ccdDetails,
                                 String caseType) {
    if (!caseTypeToJavaType.containsKey(caseType)) {
      log.warn("Handler not found for {}", caseType);
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Handler not found for " + caseType);
    }

    if (ccdDetails != null) {
      try {
        Map<String, Object> migratedData = registry.applyPreEventHooks(caseType, ccdDetails.getData());
        ccdDetails.setData(migratedData);
      } catch (Exception e) {
        log.error("Error running pre-event hooks", e);
      }
    }

    String json = mapper.writeValueAsString(ccdDetails);
    CaseDetails result = mapper.readValue(json, caseTypeToJavaType.get(caseType));
    return result;
  }
}

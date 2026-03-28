package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.api.CcdEventBinding;
import uk.gov.hmcts.ccd.sdk.runtime.DtoMapper;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.ccd.client.model.CaseDataContent;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.ccd.client.model.CaseResource;
import uk.gov.hmcts.reform.ccd.client.model.Event;
import uk.gov.hmcts.reform.ccd.client.model.StartEventResponse;

/**
 * Keeps DTO-backed cftlib tests on DTO fields instead of prefixed CCD wire keys.
 */
public class CcdEventTestClient {

  private final CoreCaseDataApi ccdApi;
  private final ObjectMapper objectMapper;
  private final ResolvedConfigRegistry resolvedConfigRegistry;

  public CcdEventTestClient(
      CoreCaseDataApi ccdApi,
      ObjectMapper objectMapper,
      ResolvedConfigRegistry resolvedConfigRegistry
  ) {
    this.ccdApi = ccdApi;
    this.objectMapper = objectMapper;
    this.resolvedConfigRegistry = resolvedConfigRegistry;
  }

  public <D> CaseDetails startAndSubmitCreateEvent(
      String idamToken,
      String s2sToken,
      String caseTypeId,
      CcdEventBinding<D> event,
      D dto
  ) {
    var resolvedEvent = getRequiredDtoEvent(caseTypeId, event);
    StartEventResponse start = ccdApi.startCase(idamToken, s2sToken, caseTypeId, event.id());

    CaseDataContent content = CaseDataContent.builder()
        .data(buildSubmissionData(start, resolvedEvent.getFieldPrefix(), dto))
        .event(Event.builder().id(event.id()).build())
        .eventToken(start.getToken())
        .build();

    return ccdApi.submitCaseCreation(idamToken, s2sToken, caseTypeId, content);
  }

  public <D> CaseResource startAndSubmitUpdateEvent(
      String idamToken,
      String s2sToken,
      String caseTypeId,
      long caseReference,
      CcdEventBinding<D> event,
      D dto
  ) {
    var resolvedEvent = getRequiredDtoEvent(caseTypeId, event);
    StartEventResponse start = ccdApi.startEvent(idamToken, s2sToken, Long.toString(caseReference), event.id());

    CaseDataContent content = CaseDataContent.builder()
        .data(buildSubmissionData(start, resolvedEvent.getFieldPrefix(), dto))
        .event(Event.builder().id(event.id()).build())
        .eventToken(start.getToken())
        .build();

    return ccdApi.createEvent(idamToken, s2sToken, Long.toString(caseReference), content);
  }

  private <D> uk.gov.hmcts.ccd.sdk.api.Event<?, ?, ?> getRequiredDtoEvent(
      String caseTypeId, CcdEventBinding<D> event) {
    uk.gov.hmcts.ccd.sdk.api.Event<?, ?, ?> resolvedEvent =
        resolvedConfigRegistry.getRequiredEvent(caseTypeId, event.id());
    if (!resolvedEvent.isDtoEvent()) {
      throw new IllegalArgumentException(event.id() + " is not configured as a DTO-backed event");
    }
    if (!event.dtoClass().equals(resolvedEvent.getDtoClass())) {
      throw new IllegalArgumentException(
          "Event " + event.id() + " expects DTO " + resolvedEvent.getDtoClass().getName()
              + " but binding declared " + event.dtoClass().getName()
      );
    }
    if (!event.fieldPrefix().equals(resolvedEvent.getFieldPrefix())) {
      throw new IllegalArgumentException(
          "Event " + event.id() + " expects field prefix " + resolvedEvent.getFieldPrefix()
              + " but binding declared " + event.fieldPrefix()
      );
    }
    return resolvedEvent;
  }

  private Map<String, Object> buildSubmissionData(StartEventResponse start, String fieldPrefix, Object dto) {
    Map<String, Object> submissionData = new LinkedHashMap<>();
    if (start.getCaseDetails() != null && start.getCaseDetails().getData() != null) {
      submissionData.putAll(start.getCaseDetails().getData());
    }
    submissionData.putAll(DtoMapper.toCcdData(dto, fieldPrefix, objectMapper));
    return submissionData;
  }
}

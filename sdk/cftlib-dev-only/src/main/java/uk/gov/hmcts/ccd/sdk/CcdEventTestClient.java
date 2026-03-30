package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import uk.gov.hmcts.ccd.sdk.runtime.DtoMapper;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.ccd.client.model.CaseDataContent;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.ccd.client.model.CaseResource;
import uk.gov.hmcts.reform.ccd.client.model.Event;
import uk.gov.hmcts.reform.ccd.client.model.StartEventResponse;

/**
 * Keeps DTO-backed cftlib tests on DTO fields instead of CCD wire keys.
 */
@RequiredArgsConstructor
public class CcdEventTestClient {

  private final CoreCaseDataApi ccdApi;
  private final ObjectMapper objectMapper;
  private final ResolvedConfigRegistry resolvedConfigRegistry;

  public <D> CaseDetails startAndSubmitCreateEvent(
      String idamToken,
      String s2sToken,
      String caseTypeId,
      String eventId,
      Class<D> dtoClass,
      D dto
  ) {
    getRequiredDtoEvent(caseTypeId, eventId, dtoClass);
    StartEventResponse start = ccdApi.startCase(idamToken, s2sToken, caseTypeId, eventId);

    CaseDataContent content = CaseDataContent.builder()
        .data(buildSubmissionData(start, dto))
        .event(Event.builder().id(eventId).build())
        .eventToken(start.getToken())
        .build();

    return ccdApi.submitCaseCreation(idamToken, s2sToken, caseTypeId, content);
  }

  public <D> CaseResource startAndSubmitUpdateEvent(
      String idamToken,
      String s2sToken,
      String caseTypeId,
      long caseReference,
      String eventId,
      Class<D> dtoClass,
      D dto
  ) {
    getRequiredDtoEvent(caseTypeId, eventId, dtoClass);
    StartEventResponse start = ccdApi.startEvent(idamToken, s2sToken, Long.toString(caseReference), eventId);

    CaseDataContent content = CaseDataContent.builder()
        .data(buildSubmissionData(start, dto))
        .event(Event.builder().id(eventId).build())
        .eventToken(start.getToken())
        .build();

    return ccdApi.createEvent(idamToken, s2sToken, Long.toString(caseReference), content);
  }

  private <D> void getRequiredDtoEvent(String caseTypeId, String eventId, Class<D> dtoClass) {
    uk.gov.hmcts.ccd.sdk.api.Event<?, ?, ?> resolvedEvent =
        resolvedConfigRegistry.getRequiredEvent(caseTypeId, eventId);
    if (!resolvedEvent.isDtoEvent()) {
      throw new IllegalArgumentException(eventId + " is not configured as a DTO-backed event");
    }
    if (!dtoClass.equals(resolvedEvent.getDtoClass())) {
      throw new IllegalArgumentException(
          "Event " + eventId + " expects DTO " + resolvedEvent.getDtoClass().getName()
              + " but caller passed " + dtoClass.getName()
      );
    }
  }

  private Map<String, Object> buildSubmissionData(StartEventResponse start, Object dto) {
    Map<String, Object> submissionData = new LinkedHashMap<>();
    if (start.getCaseDetails() != null && start.getCaseDetails().getData() != null) {
      submissionData.putAll(start.getCaseDetails().getData());
    }
    submissionData.putAll(DtoMapper.toCcdData(dto, objectMapper));
    return submissionData;
  }
}

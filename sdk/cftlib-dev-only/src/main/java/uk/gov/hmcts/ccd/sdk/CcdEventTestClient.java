package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import uk.gov.hmcts.ccd.sdk.runtime.DtoMapper;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.ccd.client.model.CaseDataContent;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.ccd.client.model.Event;
import uk.gov.hmcts.reform.ccd.client.model.StartEventResponse;

/**
 * Keeps DTO-backed cftlib tests on DTO fields instead of CCD wire keys.
 */
@RequiredArgsConstructor
public class CcdEventTestClient {

  private static final String BASE_URL = "http://localhost:4452";

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
        .data(buildSubmissionData(dto))
        .event(Event.builder().id(eventId).build())
        .eventToken(start.getToken())
        .build();

    return ccdApi.submitCaseCreation(idamToken, s2sToken, caseTypeId, content);
  }

  @SneakyThrows
  public <D> void startAndSubmitUpdateEvent(
      String idamToken,
      String s2sToken,
      String caseTypeId,
      long caseReference,
      String eventId,
      Class<D> dtoClass,
      D dto
  ) {
    getRequiredDtoEvent(caseTypeId, eventId, dtoClass);
    String caseRef = Long.toString(caseReference);
    StartEventResponse start = ccdApi.startEvent(idamToken, s2sToken, caseRef, eventId);

    var body = Map.of(
        "data", buildSubmissionData(dto),
        "event", Map.of("id", eventId),
        "event_token", start.getToken(),
        "ignore_warning", false
    );

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(BASE_URL + "/cases/" + caseRef + "/events"))
        .header("Authorization", idamToken)
        .header("ServiceAuthorization", s2sToken)
        .header("Content-Type", "application/json")
        .header("experimental", "true")
        .header("Accept",
            "application/vnd.uk.gov.hmcts.ccd-data-store-api.create-event.v2+json;charset=UTF-8")
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
        .build();

    HttpResponse<String> response = HttpClient.newHttpClient()
        .send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() >= 400) {
      throw new RuntimeException("[" + response.statusCode() + "] " + response.body());
    }
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

  private Map<String, Object> buildSubmissionData(Object dto) {
    return DtoMapper.toCcdData(dto, objectMapper);
  }
}

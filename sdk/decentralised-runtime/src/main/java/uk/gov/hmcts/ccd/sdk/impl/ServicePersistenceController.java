package uk.gov.hmcts.ccd.sdk.impl;

import java.util.List;
import java.util.UUID;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedAuditEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedSubmitEventResponse;
import uk.gov.hmcts.ccd.sdk.config.CcdCaseDataMapperConfiguration;

@Slf4j
@RestController
@RequestMapping(path = "/ccd-persistence")
class ServicePersistenceController {

  private final CaseSubmissionService submissionService;
  private final AuditEventService auditEventService;
  private final SupplementaryDataService supplementaryDataService;
  private final CaseProjectionService caseProjectionService;
  private final ObjectMapper mapper;

  ServicePersistenceController(
      CaseSubmissionService submissionService,
      AuditEventService auditEventService,
      SupplementaryDataService supplementaryDataService,
      CaseProjectionService caseProjectionService,
      @Qualifier(CcdCaseDataMapperConfiguration.CCD_CASE_DATA_OBJECT_MAPPER)
      ObjectMapper mapper) {
    this.submissionService = submissionService;
    this.auditEventService = auditEventService;
    this.supplementaryDataService = supplementaryDataService;
    this.caseProjectionService = caseProjectionService;
    this.mapper = mapper;
  }

  @SneakyThrows
  @GetMapping(
      value = "/cases",
      produces = "application/json"
  )
  public byte[] getCases(@RequestParam("case-refs") List<Long> caseRefs) {
    log.info("Fetching cases for references: {}", caseRefs);
    return mapper.writeValueAsBytes(caseProjectionService.load(caseRefs));
  }

  @SneakyThrows
  @PostMapping(
      value = "/cases/{caseRef}/supplementary-data",
      produces = "application/json"
  )
  public byte[] updateSupplementaryData(
      @PathVariable("caseRef") long caseRef,
      @RequestBody byte[] request
  ) {
    log.info("Updating supplementary data for case reference: {}", caseRef);
    var updateRequest = mapper.readValue(request, SupplementaryDataUpdateRequest.class);
    return mapper.writeValueAsBytes(supplementaryDataService.updateSupplementaryData(caseRef, updateRequest));
  }

  @SneakyThrows
  @PostMapping("/cases")
  public ResponseEntity<byte[]> createEventRequest(
      @RequestBody byte[] request,
      @RequestHeader(value = "Authorization") String authorisation,
      @RequestHeader(value = IdempotencyEnforcer.IDEMPOTENCY_KEY_HEADER) UUID idempotencyKey) {
    var event = mapper.readValue(request, DecentralisedCaseEvent.class);
    var response = createEvent(event, authorisation, idempotencyKey);
    return ResponseEntity.status(response.getStatusCode())
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(mapper.writeValueAsBytes(response.getBody()));
  }

  ResponseEntity<DecentralisedSubmitEventResponse> createEvent(
      DecentralisedCaseEvent event,
      String authorisation,
      UUID idempotencyKey) {
    if (authorisation.isBlank()) {
      var errorResponse = new DecentralisedSubmitEventResponse();
      errorResponse.setErrors(List.of("Authorization header is required"));
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }
    var response = submissionService.submit(
        event,
        authorisation,
        idempotencyKey
    );
    return ResponseEntity.ok(response);
  }

  /**
   * Retrieves the full event history for a given case.
   *
   * @param caseRef The case reference number.
   * @return A list of audit events.
   */
  @SneakyThrows
  @GetMapping(
      value = "/cases/{caseRef}/history",
      produces = "application/json"
  )
  public byte[] loadHistory(@PathVariable("caseRef") long caseRef) {
    log.info("Loading history for case reference: {}", caseRef);
    return mapper.writeValueAsBytes(auditEventService.loadHistory(caseRef));
  }

  /**
   * Retrieves a single event from the history of a given case.
   *
   * @param caseRef The case reference number.
   * @param eventId The specific event ID.
   * @return A single audit event.
   */
  @SneakyThrows
  @GetMapping(
      value = "/cases/{caseRef}/history/{eventId}",
      produces = "application/json"
  )
  public byte[] loadHistoryEvent(@PathVariable("caseRef") long caseRef,
                                 @PathVariable("eventId") long eventId) {
    log.info("Loading history event ID {} for case reference: {}", eventId, caseRef);
    DecentralisedAuditEvent event = auditEventService.loadHistoryEvent(caseRef, eventId);
    return mapper.writeValueAsBytes(event);
  }

}

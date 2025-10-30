package uk.gov.hmcts.ccd.sdk;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
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
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedAuditEvent;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedSubmitEventResponse;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedUpdateSupplementaryDataResponse;

@Slf4j
@RestController
@RequestMapping(path = "/ccd-persistence")
@RequiredArgsConstructor
public class ServicePersistenceController {

  private final CaseSubmissionService submissionService;
  private final AuditEventService auditEventService;
  private final SupplementaryDataService supplementaryDataService;
  private final CaseViewLoader caseViewLoader;

  @GetMapping(
      value = "/cases", // Mapped to the root /cases endpoint
      produces = "application/json"
  )
  public List<DecentralisedCaseDetails> getCases(@RequestParam("case-refs") List<Long> caseRefs) {
    log.info("Fetching cases for references: {}", caseRefs);
    return caseViewLoader.load(caseRefs);
  }

  @PostMapping(
      value = "/cases/{caseRef}/supplementary-data",
      produces = "application/json"
  )
  public DecentralisedUpdateSupplementaryDataResponse updateSupplementaryData(
      @PathVariable("caseRef") long caseRef,
      @RequestBody SupplementaryDataUpdateRequest request
  ) {
    log.info("Updating supplementary data for case reference: {}", caseRef);
    return supplementaryDataService.updateSupplementaryData(caseRef, request);
  }

  @SneakyThrows
  @PostMapping("/cases")
  public ResponseEntity<DecentralisedSubmitEventResponse> createEvent(
      @RequestBody DecentralisedCaseEvent event,
      @RequestHeader(value = "Authorization") String authorisation,
      @RequestHeader(value = IdempotencyEnforcer.IDEMPOTENCY_KEY_HEADER) UUID idempotencyKey) {

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
  @GetMapping(
      value = "/cases/{caseRef}/history",
      produces = "application/json"
  )
  public ResponseEntity<List<DecentralisedAuditEvent>> loadHistory(@PathVariable("caseRef") long caseRef) {
    log.info("Loading history for case reference: {}", caseRef);
    return ResponseEntity.ok(auditEventService.loadHistory(caseRef));
  }

  /**
   * Retrieves a single event from the history of a given case.
   *
   * @param caseRef The case reference number.
   * @param eventId The specific event ID.
   * @return A single audit event.
   */
  @GetMapping(
      value = "/cases/{caseRef}/history/{eventId}",
      produces = "application/json"
  )
  public ResponseEntity<DecentralisedAuditEvent> loadHistoryEvent(@PathVariable("caseRef") long caseRef,
                                                                  @PathVariable("eventId") long eventId) {
    log.info("Loading history event ID {} for case reference: {}", eventId, caseRef);
    DecentralisedAuditEvent event = auditEventService.loadHistoryEvent(caseRef, eventId);
    return ResponseEntity.ok(event);
  }

}

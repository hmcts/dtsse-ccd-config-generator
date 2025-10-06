package uk.gov.hmcts.ccd.sdk;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedAuditEvent;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedSubmitEventResponse;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedUpdateSupplementaryDataResponse;
import uk.gov.hmcts.ccd.domain.model.callbacks.AfterSubmitCallbackResponse;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

@Slf4j
@RestController
@RequestMapping(path = "/ccd-persistence")
@RequiredArgsConstructor
public class CaseController {

  private final TransactionTemplate transactionTemplate;
  private final ConfigGeneratorCallbackDispatcher dispatcher;
  private final IdempotencyEnforcer idempotencyEnforcer;
  private final IdamService idam;
  private final CaseEventHistoryService caseEventHistoryService;
  private final SupplementaryDataService supplementaryDataService;
  private final BlobRepository blobRepository;

  @GetMapping(
      value = "/cases", // Mapped to the root /cases endpoint
      produces = "application/json"
  )
  @SneakyThrows
  public List<DecentralisedCaseDetails> getCases(@RequestParam("case-refs") List<Long> caseRefs) {
    log.info("Fetching cases for references: {}", caseRefs);
    return blobRepository.getCases(caseRefs);
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
      @RequestHeader HttpHeaders headers) {

    log.info("Creating event '{}' for case reference: {}",
        event.getEventDetails().getEventId(), event.getCaseDetails().getReference());
    var referer = Objects.requireNonNullElse(headers.getFirst(HttpHeaders.REFERER), "");
    URI uri = UriComponentsBuilder.fromUriString(referer).build().toUri();
    var params = UriComponentsBuilder.fromUri(uri).build().getQueryParams();

    var user = idam.retrieveUser(headers.getFirst("Authorization"));
    AtomicReference<ConfigGeneratorCallbackDispatcher.SubmitDispatchOutcome> dispatchOutcome =
        new AtomicReference<>();
    var newRequest = Boolean.FALSE;
    try {
      newRequest = transactionTemplate.execute(status -> {
        if (idempotencyEnforcer.markProcessedReturningIsAlreadyProcessed(
            headers.getFirst(IdempotencyEnforcer.IDEMPOTENCY_KEY_HEADER))) {
          return false;
        }

        var outcome = dispatcher.prepareSubmit(event, params);
        dispatchOutcome.set(outcome);

        var submitResponse = outcome.response();
        if (submitResponse.getErrors() != null && !submitResponse.getErrors().isEmpty()) {
          throw new CallbackValidationException(submitResponse.getErrors(), submitResponse.getWarnings());
        }

        outcome.afterSubmitResponse().ifPresent(afterSubmit ->
            event.getCaseDetails().setAfterSubmitCallbackResponseEntity(ResponseEntity.ok(afterSubmit))
        );

        saveCaseReturningAuditId(event, user);
        return true;
      });
    } catch (CallbackValidationException e) {
      var response = new DecentralisedSubmitEventResponse();
      response.setErrors(e.getErrors());
      response.setWarnings(e.getWarnings());
      return ResponseEntity.ok(response);
    }

    SubmittedCallbackResponse submittedResponse = null;
    var outcome = dispatchOutcome.get();
    if (Boolean.TRUE.equals(newRequest) && outcome != null && outcome.hasSubmittedCallback()) {
      submittedResponse = dispatcher.runSubmittedCallback(event).orElse(null);
    }

    var details = blobRepository.getCase(event.getCaseDetails().getReference());
    if (submittedResponse == null) {
      submittedResponse = toSubmittedCallbackResponse(
          event.getCaseDetails().getAfterSubmitCallbackResponse());
    }
    if (submittedResponse != null) {
      var afterSubmitResponse = new AfterSubmitCallbackResponse();
      afterSubmitResponse.setConfirmationHeader(submittedResponse.getConfirmationHeader());
      afterSubmitResponse.setConfirmationBody(submittedResponse.getConfirmationBody());
      var responseEntity = ResponseEntity.ok(afterSubmitResponse);
      details.getCaseDetails().setAfterSubmitCallbackResponseEntity(responseEntity);
    }

    var response = new DecentralisedSubmitEventResponse();
    response.setCaseDetails(details);
    return ResponseEntity.ok(response);
  }

  @SneakyThrows
  private long saveCaseReturningAuditId(DecentralisedCaseEvent event, IdamService.User user) {
    try {
      long caseDataId = blobRepository.upsertCase(event);
      var currentView = blobRepository.getCase(event.getCaseDetails().getReference()).getCaseDetails();
      return caseEventHistoryService.saveAuditRecord(event, user, currentView, caseDataId);
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Case was updated concurrently");
    }
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
    return ResponseEntity.ok(caseEventHistoryService.loadHistory(caseRef));
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
    DecentralisedAuditEvent event = caseEventHistoryService.loadHistoryEvent(caseRef, eventId);
    return ResponseEntity.ok(event);
  }

  private SubmittedCallbackResponse toSubmittedCallbackResponse(AfterSubmitCallbackResponse response) {
    if (response == null) {
      return null;
    }
    if (response.getConfirmationHeader() == null && response.getConfirmationBody() == null) {
      return null;
    }
    return SubmittedCallbackResponse.builder()
        .confirmationHeader(response.getConfirmationHeader())
        .confirmationBody(response.getConfirmationBody())
        .build();
  }

}

package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;
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
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedAuditEvent;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedSubmitEventResponse;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedUpdateSupplementaryDataResponse;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping(path = "/ccd-persistence")
@RequiredArgsConstructor
public class CaseController {

  private final NamedParameterJdbcTemplate ndb;
  private final TransactionTemplate transactionTemplate;
  private final ObjectMapper defaultMapper;
  private final CCDEventListener eventListener;
  private final IdempotencyEnforcer idempotencyEnforcer;
  private final IdamService idam;
  private final CaseEventHistoryService caseEventHistoryService;
  private final SupplementaryDataService supplementaryDataService;
  private final BlobRepository caseRepository;

  @GetMapping(
      value = "/cases", // Mapped to the root /cases endpoint
      produces = "application/json"
  )
  @SneakyThrows
  public List<DecentralisedCaseDetails> getCases(@RequestParam("case-refs") List<Long> caseRefs) {
    log.info("Fetching cases for references: {}", caseRefs);
    return caseRepository.getCases(caseRefs);
  }

  @PostMapping(
      value = "/cases/{caseRef}/supplementary-data",
      produces = "application/json"
  )
  public DecentralisedUpdateSupplementaryDataResponse updateSupplementaryData(@PathVariable("caseRef") long caseRef, @RequestBody SupplementaryDataUpdateRequest request) {
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
    try {
      transactionTemplate.execute(status -> {
        if (idempotencyEnforcer.markProcessedReturningIsAlreadyProcessed(
            headers.getFirst(IdempotencyEnforcer.IDEMPOTENCY_KEY_HEADER))) {
          // TODO: Do we need to return the exact same response as before or are we ok to include subsequent changes.
          return status;
        }

        var result = dispatchAboutToSubmit(event, params);
        if (result.getErrors() != null && !result.getErrors().isEmpty()) {
          throw new CallbackValidationException(result.getErrors(), result.getWarnings());
        }

        saveCaseReturningAuditId(event, user);
        return status;
      });
    } catch (CallbackValidationException e) {
      var response = new DecentralisedSubmitEventResponse();
      response.setErrors(e.getErrors());
      response.setWarnings(e.getWarnings());
      return ResponseEntity.ok(response);
    }

    if (eventListener.hasSubmittedCallbackForEvent(event.getEventDetails().getCaseType(),
        event.getEventDetails().getEventId())) {
      dispatchSubmitted(event);
    }

    var details = caseRepository.getCase(event.getCaseDetails().getReference());
    var response = new DecentralisedSubmitEventResponse();
    response.setCaseDetails(details);
    return ResponseEntity.ok(response);
  }

  @SneakyThrows
  private long saveCaseReturningAuditId(DecentralisedCaseEvent event, IdamService.User user) {
    int version = Optional.ofNullable(event.getCaseDetails().getVersion()).orElse(1);

    String data = caseRepository.serialiseDataFilteringExternalFields(event.getCaseDetails());
    var caseDetails = event.getCaseDetails();
    // Upsert the case - create if it doesn't exist, update if it does.
    var sql = """
            insert into ccd.case_data (last_modified, last_state_modified_date, jurisdiction, case_type_id, state, data, reference, security_classification, version, id)
            values ((now() at time zone 'UTC'), (now() at time zone 'UTC'), :jurisdiction, :case_type_id, :state, (:data::jsonb), :reference, :security_classification::ccd.securityclassification, :version, :id)
            on conflict (reference)
            do update set
                state = excluded.state,
                data = excluded.data,
                security_classification = excluded.security_classification,
                last_modified = (now() at time zone 'UTC'),
                version = case
                            when
                              -- We only bump the version if a mutable field actually changes
                              case_data.data is distinct from excluded.data
                              or case_data.state is distinct from excluded.state
                              or case_data.security_classification is distinct from excluded.security_classification
                            then
                              case_data.version + 1
                            else
                              case_data.version
                          end,
                last_state_modified_date = case
                                             when case_data.state is distinct from excluded.state then (now() at time zone 'UTC')
                                             else case_data.last_state_modified_date
                                           end
                where case_data.version = excluded.version
                returning id;
            """;
    var params = Map.of(
        "jurisdiction", caseDetails.getJurisdiction(),
        "case_type_id", caseDetails.getCaseTypeId(),
        "state", event.getCaseDetails().getState(),
        "data", data,
        "reference", caseDetails.getReference(),
        "security_classification", caseDetails.getSecurityClassification().toString(),
        "version", version,
        "id", event.getInternalCaseId()
    );

    try {
      long caseDataId = ndb.queryForObject(sql, params, Long.class);
      var currentView = caseRepository.getCase(event.getCaseDetails().getReference()).getCaseDetails();
      return caseEventHistoryService.saveAuditRecord(event, user, currentView, caseDataId);
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Case was updated concurrently");
    }
  }

  @SneakyThrows
  private void dispatchSubmitted(DecentralisedCaseEvent event) {
    if (eventListener.hasSubmittedCallbackForEvent(event.getEventDetails().getCaseType(),
        event.getEventDetails().getEventId())) {
      var req = CallbackRequest.builder()
          .caseDetails(toCaseDetails(event.getCaseDetails()))
          .caseDetailsBefore(toCaseDetails(event.getCaseDetailsBefore()))
          .eventId(event.getEventDetails().getEventId())
          .build();
      eventListener.submitted(req);
    }
  }

  @SneakyThrows
  private DecentralisedSubmitEventResponse dispatchAboutToSubmit(DecentralisedCaseEvent event, MultiValueMap<String, String> urlParams) {
    var response = new DecentralisedSubmitEventResponse();
    if (eventListener.hasSubmitHandler(event.getEventDetails().getCaseType(), event.getEventDetails().getEventId())) {
      eventListener.submit(event.getEventDetails().getCaseType(), event.getEventDetails().getEventId(), event,
          urlParams);
    } else if (eventListener.hasAboutToSubmitCallbackForEvent(event.getEventDetails().getCaseType(),
        event.getEventDetails().getEventId())) {
      var req = CallbackRequest.builder()
          .caseDetails(toCaseDetails(event.getCaseDetails()))
          .caseDetailsBefore(toCaseDetails(event.getCaseDetailsBefore()))
          .eventId(event.getEventDetails().getEventId())
          .build();
      var cb = eventListener.aboutToSubmit(req);

      event.getCaseDetails().setData(defaultMapper.convertValue(cb.getData(), Map.class));
      if (cb.getState() != null) {
        event.getCaseDetails().setState(cb.getState().toString());
      }
      if (cb.getSecurityClassification() != null) {
        event.getCaseDetails().setSecurityClassification(SecurityClassification.valueOf(cb.getSecurityClassification()));
      }

      response.setErrors(cb.getErrors());
      response.setWarnings(cb.getWarnings());
    }
    return response;
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


  private CaseDetails toCaseDetails(uk.gov.hmcts.ccd.domain.model.definition.CaseDetails data) {
    return defaultMapper.convertValue(data, CaseDetails.class);
  }

}

package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.reform.ccd.client.model.SignificantItem;

/**
 * Owns the transaction ordering shared by every locally persisted CCD case event.
 */
@Service
class CaseEventTransactionCoordinator {

  private final IdempotencyEnforcer idempotencyEnforcer;
  private final TransactionTemplate transactionTemplate;
  private final AuditEventService auditEventService;
  private final CaseDataRepository caseDataRepository;
  private final CaseProjectionService caseProjectionService;

  CaseEventTransactionCoordinator(
      IdempotencyEnforcer idempotencyEnforcer,
      TransactionTemplate transactionTemplate,
      AuditEventService auditEventService,
      CaseDataRepository caseDataRepository,
      CaseProjectionService caseProjectionService
  ) {
    this.idempotencyEnforcer = idempotencyEnforcer;
    this.transactionTemplate = transactionTemplate;
    this.auditEventService = auditEventService;
    this.caseDataRepository = caseDataRepository;
    this.caseProjectionService = caseProjectionService;
  }

  <T> TransactionResult<T> execute(
      long caseReference,
      UUID idempotencyKey,
      CaseEventWork<T> work
  ) {
    return Objects.requireNonNull(transactionTemplate.execute(status ->
        executeInTransaction(caseReference, idempotencyKey, work)));
  }

  private <T> TransactionResult<T> executeInTransaction(
      long caseReference,
      UUID idempotencyKey,
      CaseEventWork<T> work
  ) {
    Optional<Long> existingEventId = idempotencyEnforcer.lockCaseAndGetExistingEvent(
        idempotencyKey,
        caseReference
    );
    if (existingEventId.isPresent()) {
      return TransactionResult.replayed(existingEventId.get());
    }

    long caseEventId = auditEventService.reserveCaseEventId();
    CaseEventWrite<T> write = Objects.requireNonNull(work.execute(), "Case event work must return a write");

    upsertCase(write.event(), write.dataUpdate());
    DecentralisedCaseDetails savedCase = caseProjectionService.load(caseReference);
    auditEventService.saveAuditRecord(
        caseEventId,
        write.event(),
        write.user(),
        savedCase.getCaseDetails(),
        idempotencyKey,
        write.significantItem(),
        write.publication()
    );

    return TransactionResult.created(caseEventId, savedCase, write.result());
  }

  private void upsertCase(DecentralisedCaseEvent event, Optional<JsonNode> dataUpdate) {
    try {
      caseDataRepository.upsertCase(event, dataUpdate);
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Case was updated concurrently", e);
    }
  }

  @FunctionalInterface
  interface CaseEventWork<T> {
    CaseEventWrite<T> execute();
  }

  record CaseEventWrite<T>(
      DecentralisedCaseEvent event,
      IdamService.User user,
      Optional<JsonNode> dataUpdate,
      Optional<SignificantItem> significantItem,
      CaseEventPublication publication,
      T result
  ) {
  }

  record CreatedEvent<T>(long eventId, DecentralisedCaseDetails savedCase, T result) {
  }

  record TransactionResult<T>(Optional<Long> existingEventId, Optional<CreatedEvent<T>> createdEvent) {

    static <T> TransactionResult<T> replayed(long eventId) {
      return new TransactionResult<>(Optional.of(eventId), Optional.empty());
    }

    static <T> TransactionResult<T> created(
        long eventId,
        DecentralisedCaseDetails savedCase,
        T result
    ) {
      return new TransactionResult<>(Optional.empty(), Optional.of(new CreatedEvent<>(eventId, savedCase, result)));
    }
  }
}

package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.reform.ccd.client.model.SignificantItem;

/**
 * Owns the transaction ordering shared by every locally persisted CCD case event.
 */
@Service
@RequiredArgsConstructor
class CaseEventTransactionCoordinator {

  private final IdempotencyEnforcer idempotencyEnforcer;
  private final AuditEventService auditEventService;
  private final CaseDataRepository caseDataRepository;
  private final CaseProjectionService caseProjectionService;

  @Transactional(rollbackFor = Exception.class)
  public <T> TransactionResult<T> execute(
      long caseReference,
      UUID idempotencyKey,
      Supplier<CaseEventWrite<T>> work
  ) {
    Optional<Long> existingEventId = idempotencyEnforcer.lockCaseAndGetExistingEvent(
        idempotencyKey,
        caseReference
    );
    if (existingEventId.isPresent()) {
      return TransactionResult.replayed(existingEventId.get());
    }

    long caseEventId = auditEventService.reserveCaseEventId();
    CaseEventWrite<T> write = Objects.requireNonNull(work.get(), "Case event work must return a write");

    TransactionAspectSupport.currentTransactionStatus().flush();
    upsertCase(write.event(), write.dataUpdate());
    DecentralisedCaseDetails savedCase = caseProjectionService.load(caseReference);
    auditEventService.saveAuditRecord(
        caseEventId,
        write.event(),
        write.user(),
        savedCase.getCaseDetails(),
        idempotencyKey,
        write.significantItem()
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

  record CaseEventWrite<T>(
      DecentralisedCaseEvent event,
      IdamService.User user,
      Optional<JsonNode> dataUpdate,
      Optional<SignificantItem> significantItem,
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

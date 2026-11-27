package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.sdk.CaseEventContext;
import uk.gov.hmcts.ccd.sdk.CaseEventHook;
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
  private final ObjectProvider<CaseEventHook> hooks;

  @Transactional(rollbackFor = Exception.class)
  public <T> TransactionResult<T> execute(
      long caseReference,
      UUID idempotencyKey,
      Long startRevision,
      Supplier<CaseEventWrite<T>> work
  ) {
    Optional<Long> existingEventId = idempotencyEnforcer.lockCaseAndGetExistingEvent(
        idempotencyKey,
        caseReference,
        startRevision
    );
    if (existingEventId.isPresent()) {
      return TransactionResult.replayed(existingEventId.get());
    }

    long caseEventId = auditEventService.reserveCaseEventId();
    CaseEventWrite<T> write = Objects.requireNonNull(work.get(), "Case event work must return a write");

    long revision = upsertCase(write.event(), write.dataUpdate());
    TransactionAspectSupport.currentTransactionStatus().flush();
    runHooks(write.event(), caseReference, caseEventId, revision);
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

  /**
   * Runs the hooks after the case row is written and before the projection is loaded for the
   * audit snapshot, so anything a hook writes is part of the snapshot. A hook that throws fails
   * the event.
   */
  private void runHooks(DecentralisedCaseEvent event, long caseReference, long caseEventId,
      long revision) {
    List<CaseEventHook> registered = hooks.orderedStream().toList();
    if (registered.isEmpty()) {
      return;
    }
    CaseEventContext context = new CaseEventContext(caseReference,
        event.getCaseDetails().getCaseTypeId(), event.getCaseDetails().getJurisdiction(),
        event.getEventDetails().getEventId(), caseEventId, revision);
    for (CaseEventHook hook : registered) {
      hook.afterCaseEvent(context);
    }
    TransactionAspectSupport.currentTransactionStatus().flush();
  }

  private long upsertCase(DecentralisedCaseEvent event, Optional<JsonNode> dataUpdate) {
    try {
      return caseDataRepository.upsert(event, dataUpdate).revision();
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

  record CreatedResult<T>(DecentralisedCaseDetails savedCase, T result) {
  }

  record TransactionResult<T>(long eventId, Optional<CreatedResult<T>> created) {

    TransactionResult {
      Objects.requireNonNull(created, "Created result optional is required");
    }

    boolean replayed() {
      return created.isEmpty();
    }

    static <T> TransactionResult<T> replayed(long eventId) {
      return new TransactionResult<>(eventId, Optional.empty());
    }

    static <T> TransactionResult<T> created(
        long eventId,
        DecentralisedCaseDetails savedCase,
        T result
    ) {
      return new TransactionResult<>(eventId, Optional.of(new CreatedResult<>(savedCase, result)));
    }
  }
}

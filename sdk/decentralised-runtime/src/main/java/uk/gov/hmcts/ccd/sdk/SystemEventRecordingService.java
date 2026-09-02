package uk.gov.hmcts.ccd.sdk;

import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedEventDetails;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.impl.CaseProjectionService;
import uk.gov.hmcts.ccd.sdk.impl.CaseSubmissionService;

/**
 * Records a system event against a case in-process, without a round trip through the CCD data store.
 * The recorded event stores a fresh case snapshot, refreshing the search index, the History tab and
 * the audit trail together. No event authorisation is applied: system events only, and the event's
 * submit handler should be free of side effects.
 */
@Service
@RequiredArgsConstructor
public class SystemEventRecordingService {

  private final CaseProjectionService caseProjectionService;
  private final ResolvedConfigRegistry resolvedConfigRegistry;
  private final CaseSubmissionService submissionService;
  private final TransactionTemplate transactionTemplate;

  /** The person the event is recorded on behalf of; the audit shows them as proxied by the system user. */
  public record ActorAttribution(String id, String firstName, String lastName) {
  }

  public void recordSystemEvent(long caseReference,
                                String eventId,
                                String authorisation,
                                String summary,
                                ActorAttribution actor) {
    recordSystemEvent(caseReference, eventId, authorisation, summary, actor, UUID.randomUUID());
  }

  public void recordSystemEvent(long caseReference,
                                String eventId,
                                String authorisation,
                                String summary,
                                ActorAttribution actor,
                                UUID idempotencyKey) {
    // the transaction gives background callers a persistence session for the projection
    transactionTemplate.executeWithoutResult(status -> {
      var current = caseProjectionService.load(caseReference);
      var caseDetails = current.getCaseDetails();
      var eventConfig = resolvedConfigRegistry.getRequiredEvent(caseDetails.getCaseTypeId(), eventId);

      rejectIfEventNotPermittedInCurrentState(eventConfig, caseDetails);

      var event = snapshotEvent(current, eventConfig, eventId, summary, actor);
      submissionService.submit(event, authorisation, idempotencyKey);
    });
  }

  private void rejectIfEventNotPermittedInCurrentState(Event<?, ?, ?> eventConfig, CaseDetails caseDetails) {
    Set<?> preState = eventConfig.getPreState();
    boolean allowedInAllStates = preState == null || preState.isEmpty();
    if (allowedInAllStates) {
      return;
    }
    boolean allowedInCurrentState = preState.stream()
        .map(Object::toString)
        .anyMatch(caseDetails.getState()::equals);
    if (!allowedInCurrentState) {
      throw new IllegalStateException(
          "Event %s is not permitted in state %s for case %s"
              .formatted(eventConfig.getId(), caseDetails.getState(), caseDetails.getReference()));
    }
  }

  private DecentralisedCaseEvent snapshotEvent(DecentralisedCaseDetails current,
                                               Event<?, ?, ?> eventConfig,
                                               String eventId,
                                               String summary,
                                               ActorAttribution actor) {
    var caseDetails = current.getCaseDetails();
    var eventDetails = DecentralisedEventDetails.builder()
        .caseType(caseDetails.getCaseTypeId())
        .eventId(eventId)
        .eventName(eventConfig.getName())
        .summary(summary)
        .proxiedBy(actor == null ? null : actor.id())
        .proxiedByFirstName(actor == null ? null : actor.firstName())
        .proxiedByLastName(actor == null ? null : actor.lastName())
        .build();

    return DecentralisedCaseEvent.builder()
        .caseDetailsBefore(caseDetails)
        .caseDetails(caseDetails)
        .eventDetails(eventDetails)
        .internalCaseId(Long.valueOf(caseDetails.getId()))
        .startRevision(current.getRevision())
        .build();
  }
}

package uk.gov.hmcts.ccd.sdk.impl;

import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedEventDetails;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedSubmitEventResponse;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;

/**
 * Records a system event against a case from inside the service, without a round trip through the
 * CCD data store. Intended for background flows that change the inputs of derived, indexed case data
 * outside a user-triggered event (for example a representation change applied by a task): recording
 * an event is what stores a fresh case snapshot, so the search index, the History tab and the audit
 * trail all pick the change up from the same write. Re-enqueueing the case for indexing alone cannot
 * do this, because the indexer re-emits the snapshot stored by the last event.
 *
 * <p>No event authorisation is applied - the service is trusting its own background code - so this
 * must only be used with system events. The event's submit handler still runs and should be free of
 * side effects when used purely as a snapshot-bearing "touch".
 *
 * <p>The optional {@link ActorAttribution} records the person the event is applied on behalf of.
 * The audit row then shows that person as the actor, proxied by the authenticated system user.
 */
@Service
@RequiredArgsConstructor
public class SystemEventRecorder {

  private final CaseProjectionService caseProjectionService;
  private final ResolvedConfigRegistry resolvedConfigRegistry;
  private final CaseSubmissionService submissionService;

  public record ActorAttribution(String id, String firstName, String lastName) {
  }

  public DecentralisedSubmitEventResponse recordSystemEvent(long caseReference,
                                                            String eventId,
                                                            String authorisation,
                                                            String summary,
                                                            ActorAttribution actor) {
    return recordSystemEvent(caseReference, eventId, authorisation, summary, actor, UUID.randomUUID());
  }

  /**
   * Overload taking an idempotency key, for callers (such as backfills) that must not record the
   * same event twice.
   */
  public DecentralisedSubmitEventResponse recordSystemEvent(long caseReference,
                                                            String eventId,
                                                            String authorisation,
                                                            String summary,
                                                            ActorAttribution actor,
                                                            UUID idempotencyKey) {
    var current = caseProjectionService.load(caseReference);
    var caseDetails = current.getCaseDetails();
    var eventConfig = resolvedConfigRegistry.getRequiredEvent(caseDetails.getCaseTypeId(), eventId);

    Set<?> preState = eventConfig.getPreState();
    if (preState != null && !preState.isEmpty()
        && preState.stream().map(Object::toString).noneMatch(caseDetails.getState()::equals)) {
      throw new IllegalStateException(
          "Event %s is not permitted in state %s for case %s"
              .formatted(eventId, caseDetails.getState(), caseReference));
    }

    var eventDetails = DecentralisedEventDetails.builder()
        .caseType(caseDetails.getCaseTypeId())
        .eventId(eventId)
        .eventName(eventConfig.getName())
        .summary(summary)
        .proxiedBy(actor == null ? null : actor.id())
        .proxiedByFirstName(actor == null ? null : actor.firstName())
        .proxiedByLastName(actor == null ? null : actor.lastName())
        .build();

    var event = DecentralisedCaseEvent.builder()
        .caseDetailsBefore(caseDetails)
        .caseDetails(caseDetails)
        .eventDetails(eventDetails)
        .internalCaseId(Long.valueOf(caseDetails.getId()))
        .startRevision(current.getRevision())
        .build();

    return submissionService.submit(event, authorisation, idempotencyKey);
  }
}

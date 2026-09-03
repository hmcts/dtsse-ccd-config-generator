package uk.gov.hmcts.ccd.sdk.impl;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedEventDetails;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.sdk.ActorAttribution;
import uk.gov.hmcts.ccd.sdk.SystemEventAction;
import uk.gov.hmcts.ccd.sdk.SystemEventExecutor;
import uk.gov.hmcts.ccd.sdk.SystemEventResult;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

@Service
@ConditionalOnProperty(prefix = SystemEventExecutorImpl.SYSTEM_USER_PREFIX, name = "id")
@Transactional(propagation = Propagation.NEVER)
class SystemEventExecutorImpl implements SystemEventExecutor {

  static final String SYSTEM_USER_PREFIX = "ccd.decentralised-runtime.system-user";

  private static final int USER_ID_MAX_LENGTH = 64;
  private static final int USER_NAME_MAX_LENGTH = 255;

  private final SystemIdentity systemIdentity;
  private final CaseEventTransactionCoordinator transactionCoordinator;
  private final CaseDataRepository caseDataRepository;

  SystemEventExecutorImpl(
      @Value("${ccd.decentralised-runtime.system-user.id:}") String systemUserId,
      @Value("${ccd.decentralised-runtime.system-user.username:}") String systemUsername,
      @Value("${ccd.decentralised-runtime.system-user.first-name:}") String systemUserFirstName,
      @Value("${ccd.decentralised-runtime.system-user.last-name:}") String systemUserLastName,
      CaseEventTransactionCoordinator transactionCoordinator,
      CaseDataRepository caseDataRepository
  ) {
    this.systemIdentity = new SystemIdentity(
        systemUserId,
        systemUsername,
        systemUserFirstName,
        systemUserLastName
    );
    validateSystemIdentity(systemIdentity);
    this.transactionCoordinator = transactionCoordinator;
    this.caseDataRepository = caseDataRepository;
  }

  @Override
  public void execute(
      long caseReference,
      UUID idempotencyKey,
      SystemEventAction action
  ) {
    execute(caseReference, Optional.empty(), idempotencyKey, action);
  }

  @Override
  public void execute(
      long caseReference,
      ActorAttribution actor,
      UUID idempotencyKey,
      SystemEventAction action
  ) {
    if (actor == null) {
      throw new IllegalArgumentException("Actor attribution is required");
    }
    execute(caseReference, Optional.of(actor), idempotencyKey, action);
  }

  private void execute(
      long caseReference,
      Optional<ActorAttribution> actor,
      UUID idempotencyKey,
      SystemEventAction action
  ) {
    validateRequest(actor, idempotencyKey, action);
    transactionCoordinator.execute(
        caseReference,
        idempotencyKey,
        () -> prepareSystemEvent(caseReference, actor, action)
    );
  }

  private CaseEventTransactionCoordinator.CaseEventWrite<Void> prepareSystemEvent(
      long caseReference,
      Optional<ActorAttribution> actor,
      SystemEventAction action
  ) {
    CaseDetails currentCase = caseDataRepository.getCase(caseReference).getCaseDetails();
    final String previousState = currentCase.getState();

    SystemEventResult result = action.execute();
    if (result == null) {
      throw new IllegalArgumentException("System event action must return a result");
    }
    validateResult(result);
    result.state().ifPresent(state -> currentCase.setState(String.valueOf(state)));

    var eventDetailsBuilder = DecentralisedEventDetails.builder()
        .caseType(currentCase.getCaseTypeId())
        .eventId(result.eventId())
        .eventName(result.eventName())
        .summary(result.summary().orElse(null));
    actor.ifPresent(value -> eventDetailsBuilder
        .proxiedBy(value.id())
        .proxiedByFirstName(value.firstName())
        .proxiedByLastName(value.lastName()));

    var before = new CaseDetails();
    before.setState(previousState);
    var event = DecentralisedCaseEvent.builder()
        .caseDetailsBefore(before)
        .caseDetails(currentCase)
        .eventDetails(eventDetailsBuilder.build())
        .resolvedTtl(currentCase.getResolvedTTL())
        .internalCaseId(Long.valueOf(currentCase.getId()))
        .build();

    return new CaseEventTransactionCoordinator.CaseEventWrite<>(
        event,
        systemUser(),
        Optional.empty(),
        Optional.empty(),
        null
    );
  }

  private void validateResult(SystemEventResult result) {
    requireText(result.eventId(), "System event ID");
    requireText(result.eventName(), "System event name");
  }

  private void validateRequest(
      Optional<ActorAttribution> actor,
      UUID idempotencyKey,
      SystemEventAction action
  ) {
    if (idempotencyKey == null) {
      throw new IllegalArgumentException("System event idempotency key is required");
    }
    if (action == null) {
      throw new IllegalArgumentException("System event action is required");
    }
    actor.ifPresent(value -> {
      requireText(value.id(), "Actor ID", USER_ID_MAX_LENGTH);
      requireText(value.firstName(), "Actor first name", USER_NAME_MAX_LENGTH);
      requireText(value.lastName(), "Actor last name", USER_NAME_MAX_LENGTH);
    });
  }

  private void validateSystemIdentity(SystemIdentity identity) {
    requireText(identity.id(), "System user ID", USER_ID_MAX_LENGTH);
    requireText(identity.username(), "System username", USER_NAME_MAX_LENGTH);
    requireText(identity.firstName(), "System user first name", USER_NAME_MAX_LENGTH);
    requireText(identity.lastName(), "System user last name", USER_NAME_MAX_LENGTH);
  }

  private IdamService.User systemUser() {
    return new IdamService.User(
        null,
        new UserInfo(
            systemIdentity.id(),
            systemIdentity.id(),
            systemIdentity.username(),
            systemIdentity.firstName(),
            systemIdentity.lastName(),
            List.of("system")
        )
    );
  }

  private void requireText(String value, String field, int maxLength) {
    requireText(value, field);
    if (value.codePointCount(0, value.length()) > maxLength) {
      throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
    }
  }

  private void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }

  private record SystemIdentity(String id, String username, String firstName, String lastName) {
  }
}

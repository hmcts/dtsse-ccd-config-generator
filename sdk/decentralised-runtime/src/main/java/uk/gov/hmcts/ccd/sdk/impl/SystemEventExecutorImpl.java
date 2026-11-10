package uk.gov.hmcts.ccd.sdk.impl;

import static org.springframework.util.StringUtils.hasText;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedEventDetails;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.sdk.ActorAttribution;
import uk.gov.hmcts.ccd.sdk.SystemEventAction;
import uk.gov.hmcts.ccd.sdk.SystemEventExecutionContext;
import uk.gov.hmcts.ccd.sdk.SystemEventExecutionResult;
import uk.gov.hmcts.ccd.sdk.SystemEventExecutor;
import uk.gov.hmcts.ccd.sdk.SystemEventResult;
import uk.gov.hmcts.ccd.sdk.impl.SystemIdentityResolver.SystemIdentity;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

@Service
@Conditional(SystemEventExecutorImpl.SystemUserConfigured.class)
@Transactional(propagation = Propagation.NEVER)
class SystemEventExecutorImpl implements SystemEventExecutor {

  static final String SYSTEM_USER_PREFIX = "ccd.decentralised-runtime.system-user";

  private static final int EVENT_ID_MAX_LENGTH = 70;
  private static final int EVENT_NAME_MAX_LENGTH = 30;
  private static final int EVENT_SUMMARY_MAX_LENGTH = 1024;
  private static final int STATE_ID_MAX_LENGTH = 255;
  private static final int USER_ID_MAX_LENGTH = 64;
  private static final int USER_NAME_MAX_LENGTH = 255;

  private final SystemIdentityResolver systemIdentity;
  private final CaseEventTransactionCoordinator transactionCoordinator;
  private final CaseDataRepository caseDataRepository;

  SystemEventExecutorImpl(
      @Value("${ccd.decentralised-runtime.system-user.id:}") String systemUserId,
      @Value("${ccd.decentralised-runtime.system-user.username:}") String systemUsername,
      @Value("${ccd.decentralised-runtime.system-user.first-name:}") String systemUserFirstName,
      @Value("${ccd.decentralised-runtime.system-user.last-name:}") String systemUserLastName,
      @Value("${ccd.decentralised-runtime.system-user.password:}") String systemUserPassword,
      CaseEventTransactionCoordinator transactionCoordinator,
      CaseDataRepository caseDataRepository,
      IdamService idamService
  ) {
    if (hasText(systemUserId)) {
      var identity = new SystemIdentity(
          systemUserId,
          systemUsername,
          systemUserFirstName,
          systemUserLastName
      );
      validateSystemIdentity(identity);
      this.systemIdentity = SystemIdentityResolver.fixed(identity);
    } else {
      requireText(systemUsername, "System username", USER_NAME_MAX_LENGTH);
      requireText(systemUserPassword, "System user password");
      this.systemIdentity = SystemIdentityResolver.fromIdam(
          idamService,
          systemUsername,
          systemUserPassword,
          systemUserFirstName,
          systemUserLastName,
          this::validateSystemIdentity
      );
    }
    this.transactionCoordinator = transactionCoordinator;
    this.caseDataRepository = caseDataRepository;
  }

  @Override
  public SystemEventExecutionResult execute(
      long caseReference,
      UUID idempotencyKey,
      SystemEventAction action
  ) {
    return execute(caseReference, Optional.empty(), idempotencyKey, action);
  }

  @Override
  public SystemEventExecutionResult execute(
      long caseReference,
      ActorAttribution actor,
      UUID idempotencyKey,
      SystemEventAction action
  ) {
    if (actor == null) {
      throw new IllegalArgumentException("Actor attribution is required");
    }
    return execute(caseReference, Optional.of(actor), idempotencyKey, action);
  }

  private SystemEventExecutionResult execute(
      long caseReference,
      Optional<ActorAttribution> actor,
      UUID idempotencyKey,
      SystemEventAction action
  ) {
    validateRequest(actor, idempotencyKey, action);
    // Resolved before the transaction opens, so a first-use IDAM lookup never holds the case lock.
    SystemIdentity identity = systemIdentity.get();
    var transactionResult = transactionCoordinator.execute(
        caseReference,
        idempotencyKey,
        null,
        () -> prepareSystemEvent(caseReference, actor, idempotencyKey, action, identity)
    );
    return new SystemEventExecutionResult(
        transactionResult.eventId(),
        transactionResult.replayed()
            ? SystemEventExecutionResult.Outcome.REPLAYED
            : SystemEventExecutionResult.Outcome.EXECUTED
    );
  }

  private CaseEventTransactionCoordinator.CaseEventWrite<Void> prepareSystemEvent(
      long caseReference,
      Optional<ActorAttribution> actor,
      UUID idempotencyKey,
      SystemEventAction action,
      SystemIdentity identity
  ) {
    CaseDetails currentCase = caseDataRepository.getCase(caseReference).getCaseDetails();
    final String previousState = currentCase.getState();

    var context = new SystemEventExecutionContext(
        caseReference,
        idempotencyKey,
        currentCase.getCaseTypeId(),
        previousState
    );
    SystemEventResult result = action.execute(context);
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
        systemUser(identity),
        Optional.empty(),
        Optional.empty(),
        null
    );
  }

  private void validateResult(SystemEventResult result) {
    requireText(result.eventId(), "System event ID", EVENT_ID_MAX_LENGTH);
    requireText(result.eventName(), "System event name", EVENT_NAME_MAX_LENGTH);
    result.summary().ifPresent(summary ->
        requireMaxLength(summary, "System event summary", EVENT_SUMMARY_MAX_LENGTH)
    );
    result.state().ifPresent(state ->
        requireMaxLength(String.valueOf(state), "System event state", STATE_ID_MAX_LENGTH)
    );
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

  private IdamService.User systemUser(SystemIdentity identity) {
    return new IdamService.User(
        null,
        new UserInfo(
            identity.id(),
            identity.id(),
            identity.username(),
            identity.firstName(),
            identity.lastName(),
            List.of("system")
        )
    );
  }

  private void requireText(String value, String field, int maxLength) {
    requireText(value, field);
    requireMaxLength(value, field, maxLength);
  }

  private void requireText(String value, String field) {
    if (!hasText(value)) {
      throw new IllegalArgumentException(field + " is required");
    }
  }

  private void requireMaxLength(String value, String field, int maxLength) {
    if (value.codePointCount(0, value.length()) > maxLength) {
      throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
    }
  }

  /**
   * Matches when the system user is configured by {@code id}, or by {@code password} for IDAM
   * lookup. A blank value, such as an unset {@code ${VAR:}} placeholder, does not count.
   */
  static class SystemUserConfigured extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
      Environment environment = context.getEnvironment();
      if (hasText(environment.getProperty(SYSTEM_USER_PREFIX + ".id"))
          || hasText(environment.getProperty(SYSTEM_USER_PREFIX + ".password"))) {
        return ConditionOutcome.match("System user id or password is configured");
      }
      return ConditionOutcome.noMatch("System user id and password are not configured");
    }
  }
}

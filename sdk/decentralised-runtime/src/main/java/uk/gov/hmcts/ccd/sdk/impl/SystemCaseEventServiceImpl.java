package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedEventDetails;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.SystemCaseEvent;
import uk.gov.hmcts.ccd.sdk.SystemCaseEventAction;
import uk.gov.hmcts.ccd.sdk.SystemCaseEventActor;
import uk.gov.hmcts.ccd.sdk.SystemCaseEventContext;
import uk.gov.hmcts.ccd.sdk.SystemCaseEventOutcome;
import uk.gov.hmcts.ccd.sdk.SystemCaseEventResult;
import uk.gov.hmcts.ccd.sdk.SystemCaseEventService;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

@Service
class SystemCaseEventServiceImpl implements SystemCaseEventService {

  private static final int EVENT_ID_MAX_LENGTH = 70;
  private static final int EVENT_NAME_MAX_LENGTH = 30;
  private static final int USER_ID_MAX_LENGTH = 64;

  private final String serviceId;
  private final TransactionTemplate transactionTemplate;
  private final IdempotencyEnforcer idempotencyEnforcer;
  private final DatabaseAuditContext databaseAuditContext;
  private final ObjectProvider<CaseDataRepository> caseDataRepository;
  private final ObjectProvider<AuditEventService> auditEventService;
  private final ObjectProvider<ResolvedConfigRegistry> configRegistry;
  private final ObjectMapper mapper;

  SystemCaseEventServiceImpl(
      @Value("${ccd.decentralised-runtime.system-events.service-id:}") String serviceId,
      TransactionTemplate transactionTemplate,
      IdempotencyEnforcer idempotencyEnforcer,
      DatabaseAuditContext databaseAuditContext,
      ObjectProvider<CaseDataRepository> caseDataRepository,
      ObjectProvider<AuditEventService> auditEventService,
      ObjectProvider<ResolvedConfigRegistry> configRegistry,
      ObjectMapper mapper
  ) {
    this.serviceId = serviceId;
    this.transactionTemplate = transactionTemplate;
    this.idempotencyEnforcer = idempotencyEnforcer;
    this.databaseAuditContext = databaseAuditContext;
    this.caseDataRepository = caseDataRepository;
    this.auditEventService = auditEventService;
    this.configRegistry = configRegistry;
    this.mapper = mapper;
  }

  @Override
  public <T, S> SystemCaseEventResult submitOnBehalfOf(
      long caseReference,
      SystemCaseEvent event,
      UUID idempotencyKey,
      SystemCaseEventActor actor,
      SystemCaseEventAction<T, S> action
  ) {
    return submit(caseReference, event, idempotencyKey, Optional.of(actor), action);
  }

  @Override
  public <T, S> SystemCaseEventResult submit(
      long caseReference,
      SystemCaseEvent event,
      UUID idempotencyKey,
      SystemCaseEventAction<T, S> action
  ) {
    return submit(caseReference, event, idempotencyKey, Optional.empty(), action);
  }

  private <T, S> SystemCaseEventResult submit(
      long caseReference,
      SystemCaseEvent event,
      UUID idempotencyKey,
      Optional<SystemCaseEventActor> actor,
      SystemCaseEventAction<T, S> action
  ) {
    validate(event, idempotencyKey, actor, action);
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("System case events cannot be submitted from an existing transaction");
    }

    return transactionTemplate.execute(status -> submitInTransaction(
        caseReference, event, idempotencyKey, actor, action
    ));
  }

  private <T, S> SystemCaseEventResult submitInTransaction(
      long caseReference,
      SystemCaseEvent event,
      UUID idempotencyKey,
      Optional<SystemCaseEventActor> actor,
      SystemCaseEventAction<T, S> action
  ) {
    Optional<Long> existingEventId = idempotencyEnforcer.lockCaseAndGetExistingEvent(
        idempotencyKey, caseReference
    );
    if (existingEventId.isPresent()) {
      return new SystemCaseEventResult(caseReference, existingEventId.get(), true);
    }

    CaseDetails currentCase = caseDataRepository.getObject().getCase(caseReference).getCaseDetails();
    ResolvedCCDConfig<?, ?, ?> config = configRegistry.getObject().getRequired(currentCase.getCaseTypeId());
    final long caseEventId = databaseAuditContext.reserveCaseEventId();

    SystemCaseEventContext<T, S> context = context(caseReference, currentCase, config);
    SystemCaseEventOutcome<S> outcome = action.execute(context);
    if (outcome == null) {
      throw new IllegalArgumentException("System case event action must return an outcome");
    }

    CaseDetails before = new CaseDetails();
    before.setState(currentCase.getState());
    outcome.state().ifPresent(state -> currentCase.setState(validateState(config, state)));

    DecentralisedEventDetails eventDetails = eventDetails(currentCase, event, outcome, actor);
    DecentralisedCaseEvent caseEvent = DecentralisedCaseEvent.builder()
        .caseDetailsBefore(before)
        .caseDetails(currentCase)
        .eventDetails(eventDetails)
        .internalCaseId(Long.valueOf(currentCase.getId()))
        .build();

    caseDataRepository.getObject().upsertCase(caseEvent, Optional.empty());
    CaseDetails savedCase = caseDataRepository.getObject().getCase(caseReference).getCaseDetails();
    auditEventService.getObject().saveSystemAuditRecord(
        caseEventId,
        caseEvent,
        systemUser(),
        savedCase,
        idempotencyKey
    );

    return new SystemCaseEventResult(caseReference, caseEventId, false);
  }

  @SuppressWarnings("unchecked")
  private <T, S> SystemCaseEventContext<T, S> context(
      long caseReference,
      CaseDetails currentCase,
      ResolvedCCDConfig<?, ?, ?> config
  ) {
    T caseData = (T) mapper.convertValue(currentCase.getData(), config.getCaseClass());
    Class<?> stateClass = config.getStateClass();
    S state = (S) Enum.valueOf(
        stateClass.asSubclass(Enum.class),
        currentCase.getState()
    );
    return new SystemCaseEventContext<>(caseReference, caseData, state);
  }

  private <S> String validateState(ResolvedCCDConfig<?, ?, ?> config, S state) {
    if (state == null || !config.getStateClass().isInstance(state)) {
      throw new IllegalArgumentException("System case event requested an unknown case state");
    }
    return String.valueOf(state);
  }

  private <S> DecentralisedEventDetails eventDetails(
      CaseDetails currentCase,
      SystemCaseEvent event,
      SystemCaseEventOutcome<S> outcome,
      Optional<SystemCaseEventActor> actor
  ) {
    var builder = DecentralisedEventDetails.builder()
        .caseType(currentCase.getCaseTypeId())
        .eventId(event.id())
        .eventName(event.name())
        .summary(outcome.summary().orElse(null))
        .description(outcome.description().orElse(null));
    actor.map(this::actorIdentity).ifPresent(identity -> builder
        .proxiedBy(identity.userId())
        .proxiedByFirstName(identity.firstName())
        .proxiedByLastName(identity.lastName()));
    return builder.build();
  }

  private ActorIdentity actorIdentity(SystemCaseEventActor actor) {
    return switch (actor) {
      case SystemCaseEventActor.IdamUser idamUser -> {
        UserInfo identity = idamUser.identity();
        yield new ActorIdentity(
            identity.getUid(),
            identityPart(identity.getGivenName(), identity.getName()),
            identityPart(identity.getFamilyName(), identity.getName())
        );
      }
      case SystemCaseEventActor.Service service -> new ActorIdentity(
          "system:" + service.serviceId(),
          "System",
          service.serviceId()
      );
    };
  }

  private String identityPart(String preferred, String fallback) {
    if (preferred != null && !preferred.isBlank()) {
      return preferred;
    }
    if (fallback != null && !fallback.isBlank()) {
      return fallback;
    }
    return "Unknown";
  }

  private IdamService.User systemUser() {
    String userId = "system:" + serviceId;
    return new IdamService.User(
        null,
        new UserInfo(userId, userId, userId, "System", serviceId, List.of("system"))
    );
  }

  private <T, S> void validate(
      SystemCaseEvent event,
      UUID idempotencyKey,
      Optional<SystemCaseEventActor> actor,
      SystemCaseEventAction<T, S> action
  ) {
    requireText(serviceId, "System event service ID", USER_ID_MAX_LENGTH - "system:".length());
    if (event == null) {
      throw new IllegalArgumentException("System case event is required");
    }
    requireText(event.id(), "System case event ID", EVENT_ID_MAX_LENGTH);
    requireText(event.name(), "System case event name", EVENT_NAME_MAX_LENGTH);
    if (idempotencyKey == null) {
      throw new IllegalArgumentException("System case event idempotency key is required");
    }
    if (action == null) {
      throw new IllegalArgumentException("System case event action is required");
    }
    actor.ifPresent(this::validateActor);
  }

  private void validateActor(SystemCaseEventActor actor) {
    switch (actor) {
      case SystemCaseEventActor.IdamUser idamUser ->
          requireText(idamUser.identity().getUid(), "On-behalf-of IDAM user ID", USER_ID_MAX_LENGTH);
      case SystemCaseEventActor.Service service ->
          requireText(service.serviceId(), "On-behalf-of service ID", USER_ID_MAX_LENGTH - "system:".length());
    }

    ActorIdentity identity = actorIdentity(actor);
    requireText(identity.firstName(), "On-behalf-of first name", 255);
    requireText(identity.lastName(), "On-behalf-of last name", 255);
  }

  private void requireText(String value, String field, int maxLength) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    if (value.getBytes(StandardCharsets.UTF_8).length > maxLength) {
      throw new IllegalArgumentException(field + " exceeds " + maxLength + " bytes");
    }
  }

  private record ActorIdentity(String userId, String firstName, String lastName) {
  }
}

package uk.gov.hmcts.ccd.sdk.testing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedEventDetails;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedSubmitEventResponse;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.impl.CaseSubmissionService;

/** Exercises registered CCD events through the real runtime and database. */
public final class CcdEventTestSupport<Case, State extends Enum<State>> {

  private static final TypeReference<Map<String, JsonNode>> JSON_NODE_MAP = new TypeReference<>() {};
  private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};

  private final Class<Case> caseClass;
  private final Class<State> stateClass;
  private final ResolvedConfigRegistry registry;
  private final CaseSubmissionService submissionService;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final TestIdamService idam;

  CcdEventTestSupport(Class<Case> caseClass,
                      Class<State> stateClass,
                      ResolvedConfigRegistry registry,
                      CaseSubmissionService submissionService,
                      JdbcTemplate jdbc,
                      ObjectMapper mapper,
                      TestIdamService idam) {
    this.caseClass = caseClass;
    this.stateClass = stateClass;
    this.registry = registry;
    this.submissionService = submissionService;
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.idam = idam;
  }

  public CaseType forCaseType(String caseTypeId) {
    ResolvedCCDConfig<?, ?, ?> config = registry.getRequired(caseTypeId);
    if (!caseClass.equals(config.getCaseClass()) || !stateClass.equals(config.getStateClass())) {
      throw new IllegalArgumentException("Case type " + caseTypeId + " has different case or state types");
    }
    return new CaseType(caseTypeId, config);
  }

  /** Selects the only matching case type, or asks the test to specify one. */
  public CaseType caseType() {
    List<String> matching = registry.getAll().stream()
        .filter(config -> caseClass.equals(config.getCaseClass()) && stateClass.equals(config.getStateClass()))
        .map(ResolvedCCDConfig::getCaseType)
        .toList();
    if (matching.size() != 1) {
      throw new IllegalStateException("Expected one case type for " + caseClass.getName()
          + ", found " + matching + "; use forCaseType(id)");
    }
    return forCaseType(matching.getFirst());
  }

  public Actor registerActor(ActorDetails actor) {
    return new Actor(idam.register("ccd-sdk-test-" + UUID.randomUUID(), actor));
  }

  public long seed(State state, Case data) {
    return caseType().seed(state, data);
  }

  public CaseType.EventSubmission event(long reference, String eventId, Case submittedData) {
    return caseType().event(reference, eventId, submittedData);
  }

  public CaseType.CreateSubmission create(String eventId, State initialState, Case submittedData) {
    return caseType().create(eventId, initialState, submittedData);
  }

  public CaseType.Seed seedCase(State state, Case data) {
    return caseType().seedCase(state, data);
  }

  public CaseSnapshot snapshot(long reference) {
    return caseType().snapshot(reference);
  }

  public Case storedData(long reference) {
    return caseType().storedData(reference);
  }

  public static final class Actor {
    private final String authorisation;

    private Actor(String authorisation) {
      this.authorisation = authorisation;
    }
  }

  public final class CaseType {

    private final String caseTypeId;
    private final ResolvedCCDConfig<?, ?, ?> config;

    private CaseType(String caseTypeId, ResolvedCCDConfig<?, ?, ?> config) {
      this.caseTypeId = caseTypeId;
      this.config = config;
    }

    /** Allocates a 16-digit reference in the test database. */
    public long allocateReference() {
      jdbc.execute("create sequence if not exists ccd.sdk_test_reference_seq start with 9000000000000000");
      return Objects.requireNonNull(jdbc.queryForObject(
          "select nextval('ccd.sdk_test_reference_seq')", Long.class));
    }

    /** Inserts a case fixture without creating an audit event. */
    public long seed(State state, Case data) {
      return seedCase(state, data).insert();
    }

    public void seed(long reference, State state, Case data) {
      seedCase(state, data).reference(reference).insert();
    }

    public Seed seedCase(State state, Case data) {
      return new Seed(state, data);
    }

    public final class Seed {
      private final State state;
      private final Case data;
      private Long reference;
      private Map<String, ?> supplementaryData = Map.of();
      private TestClassification classification = TestClassification.PUBLIC;
      private LocalDate ttl;

      private Seed(State state, Case data) {
        this.state = state;
        this.data = data;
      }

      public Seed reference(long value) {
        this.reference = value;
        return this;
      }

      public Seed supplementaryData(Map<String, ?> value) {
        this.supplementaryData = Map.copyOf(value);
        return this;
      }

      public Seed classification(TestClassification value) {
        this.classification = Objects.requireNonNull(value);
        return this;
      }

      public Seed ttl(LocalDate value) {
        this.ttl = value;
        return this;
      }

      public long insert() {
        long caseReference = reference == null ? allocateReference() : reference;
        jdbc.update("""
          insert into ccd.case_data (
              id, reference, security_classification, jurisdiction, case_type_id, state,
              data, supplementary_data, resolved_ttl, version, case_revision
          ) values (?, ?, ?::ccd.securityclassification, ?, ?, ?, ?::jsonb, ?::jsonb, ?, 1, 0)
            """,
            caseReference, caseReference, classification.name(), config.getJurId(), caseTypeId,
            state.name(), json(data), json(supplementaryData), ttl == null ? null : Date.valueOf(ttl));
        return caseReference;
      }
    }

    public EventSubmission event(long reference, String eventId, Case submittedData) {
      return new EventSubmission(reference, eventId, submittedData);
    }

    /** Runs a configured creation event and records its audit history. */
    public CreateSubmission create(String eventId, State initialState, Case submittedData) {
      return new CreateSubmission(allocateReference(), eventId, initialState, submittedData);
    }

    public final class EventSubmission {
      private final long reference;
      private final String eventId;
      private final Case submittedData;
      private UUID idempotencyKey = UUID.randomUUID();
      private String authorisation = TestIdamService.DEFAULT_TOKEN;
      private Long startRevision;

      private EventSubmission(long reference, String eventId, Case submittedData) {
        this.reference = reference;
        this.eventId = eventId;
        this.submittedData = submittedData;
      }

      public long reference() {
        return reference;
      }

      public EventSubmission as(Actor actor) {
        this.authorisation = Objects.requireNonNull(actor).authorisation;
        return this;
      }

      public EventSubmission atRevision(long revision) {
        this.startRevision = revision;
        return this;
      }

      public EventSubmission withIdempotencyKey(UUID key) {
        this.idempotencyKey = key;
        return this;
      }

      public Submission submit() {
        return submitInternal(reference, eventId, submittedData, null,
            idempotencyKey, authorisation, startRevision);
      }

      public Accepted submitExpectingSuccess() {
        Submission result = submit();
        if (result instanceof Accepted accepted) {
          return accepted;
        }
        throw new AssertionError("Expected accepted event " + eventId + ", got errors "
            + ((Rejected) result).errors());
      }

      public Rejected submitExpectingErrors() {
        Submission result = submit();
        if (result instanceof Rejected rejected) {
          return rejected;
        }
        throw new AssertionError("Expected validation errors from event " + eventId);
      }
    }

    public final class CreateSubmission {
      private final long reference;
      private final String eventId;
      private final State initialState;
      private final Case submittedData;
      private UUID idempotencyKey = UUID.randomUUID();
      private String authorisation = TestIdamService.DEFAULT_TOKEN;

      private CreateSubmission(long reference, String eventId, State initialState, Case submittedData) {
        this.reference = reference;
        this.eventId = eventId;
        this.initialState = initialState;
        this.submittedData = submittedData;
      }

      public long reference() {
        return reference;
      }

      public CreateSubmission as(Actor actor) {
        this.authorisation = Objects.requireNonNull(actor).authorisation;
        return this;
      }

      public CreateSubmission withIdempotencyKey(UUID key) {
        this.idempotencyKey = key;
        return this;
      }

      public Submission submit() {
        return submitInternal(reference, eventId, submittedData, initialState,
            idempotencyKey, authorisation, null);
      }

      public Accepted submitExpectingSuccess() {
        Submission result = submit();
        if (result instanceof Accepted accepted) {
          return accepted;
        }
        throw new AssertionError("Expected created case from event " + eventId + ", got errors "
            + ((CreationRejected) result).errors());
      }

      public CreationRejected submitExpectingErrors() {
        Submission result = submit();
        if (result instanceof CreationRejected rejected) {
          return rejected;
        }
        throw new AssertionError("Expected validation errors from creation event " + eventId);
      }
    }

    private Submission submitInternal(long reference,
                                      String eventId,
                                      Case submittedData,
                                      State initialState,
                                      UUID idempotencyKey,
                                      String authorisation,
                                      Long startRevision) {
      Event<?, ?, ?> eventConfig = registry.getRequiredEvent(caseTypeId, eventId);
      Map<String, Object> stored = initialState == null ? jdbc.queryForMap("""
          select id, version, case_revision, state, data::text as data,
                 supplementary_data::text as supplementary_data,
                 security_classification::text as security_classification,
                 created_date, last_modified, last_state_modified_date, resolved_ttl
          from ccd.case_data where reference = ?
          """, reference) : null;
      checkAllowedState(eventConfig, eventId, stored, initialState);
      CaseDetails before = stored == null ? null : caseDetails(reference, stored,
          mapper.convertValue(fromJson((String) stored.get("data")), JSON_NODE_MAP));
      CaseDetails submitted = stored == null ? newCaseDetails(reference, initialState, submittedData)
          : caseDetails(reference, stored, mapper.convertValue(submittedData, JSON_NODE_MAP));
      DecentralisedCaseEvent event = DecentralisedCaseEvent.builder()
          .caseDetailsBefore(before)
          .caseDetails(submitted)
          .internalCaseId(stored == null ? reference : ((Number) stored.get("id")).longValue())
          .startRevision(stored == null ? null
              : startRevision == null ? ((Number) stored.get("case_revision")).longValue() : startRevision)
          .eventDetails(DecentralisedEventDetails.builder()
              .caseType(caseTypeId)
              .eventId(eventId)
              .eventName(Objects.requireNonNullElse(eventConfig.getName(), eventId))
              .build())
          .build();

      DecentralisedSubmitEventResponse response = submissionService.submit(event, authorisation, idempotencyKey);
      if (response.getCaseDetails() == null) {
        return stored == null ? new CreationRejected(response) : new Rejected(response, snapshot(reference));
      }
      Case projected = mapper.convertValue(response.getCaseDetails().getCaseDetails().getData(), caseClass);
      return new Accepted(response, projected, snapshot(reference), audit(reference, idempotencyKey));
    }

    private void checkAllowedState(Event<?, ?, ?> eventConfig,
                                   String eventId,
                                   Map<String, Object> stored,
                                   State initialState) {
      Set<?> allowed = eventConfig.getPreState();
      if (stored == null) {
        if (!allowed.isEmpty() || !eventConfig.getPostState().contains(initialState)) {
          throw new IllegalStateException("Event " + eventId + " is not a creation event for state " + initialState);
        }
        return;
      }
      State current = Enum.valueOf(stateClass, (String) stored.get("state"));
      if (!allowed.contains(current)) {
        throw new IllegalStateException("Event " + eventId + " is unavailable in state " + current
            + "; allowed pre-states: " + allowed);
      }
    }

    private CaseDetails newCaseDetails(long reference, State state, Case data) {
      CaseDetails details = new CaseDetails();
      details.setId(String.valueOf(reference));
      details.setReference(reference);
      details.setCaseTypeId(caseTypeId);
      details.setJurisdiction(config.getJurId());
      details.setState(state.name());
      details.setVersion(1);
      details.setRevision(0L);
      details.setSecurityClassification(SecurityClassification.PUBLIC);
      details.setData(mapper.convertValue(data, JSON_NODE_MAP));
      details.setSupplementaryData(Map.of());
      return details;
    }

    /** Reads ccd.case_data.data directly, before CaseView projection. */
    public JsonNode rawData(long reference) {
      return snapshot(reference).rawData();
    }

    /** Deserialises the stored blob without applying CaseView projection. */
    public Case storedData(long reference) {
      return mapper.convertValue(rawData(reference), caseClass);
    }

    /** Reads persisted blob data and both version counters after a submission. */
    public CaseSnapshot snapshot(long reference) {
      Map<String, Object> row = jdbc.queryForMap(
          """
          select data::text as data, version, case_revision, state,
                 security_classification::text as classification,
                 supplementary_data::text as supplementary_data
          from ccd.case_data where reference = ?
          """, reference);
      String supplementary = (String) row.get("supplementary_data");
      return new CaseSnapshot(fromJson((String) row.get("data")),
          ((Number) row.get("version")).intValue(), ((Number) row.get("case_revision")).longValue(),
          (String) row.get("state"), TestClassification.valueOf((String) row.get("classification")),
          supplementary == null ? Map.of() : mapper.convertValue(fromJson(supplementary), OBJECT_MAP));
    }

    private Audit audit(long reference, UUID idempotencyKey) {
      Map<String, Object> row = jdbc.queryForMap("""
          select ce.id, ce.event_id, ce.version, ce.case_revision
          from ccd.case_event ce
          join ccd.case_data cd on cd.id = ce.case_data_id
          where cd.reference = ? and ce.idempotency_key = ?
          """, reference, idempotencyKey);
      return new Audit(((Number) row.get("id")).longValue(), (String) row.get("event_id"),
          ((Number) row.get("version")).intValue(), ((Number) row.get("case_revision")).longValue());
    }

    private CaseDetails caseDetails(long reference, Map<String, Object> stored, Map<String, JsonNode> data) {
      CaseDetails details = new CaseDetails();
      details.setId(String.valueOf(stored.get("id")));
      details.setReference(reference);
      details.setCaseTypeId(caseTypeId);
      details.setJurisdiction(config.getJurId());
      details.setState((String) stored.get("state"));
      details.setVersion(((Number) stored.get("version")).intValue());
      details.setRevision(((Number) stored.get("case_revision")).longValue());
      details.setCreatedDate(dateTime(stored.get("created_date")));
      details.setLastModified(dateTime(stored.get("last_modified")));
      details.setLastStateModifiedDate(dateTime(stored.get("last_state_modified_date")));
      Date resolvedTtl = (Date) stored.get("resolved_ttl");
      details.setResolvedTTL(resolvedTtl == null ? null : resolvedTtl.toLocalDate());
      details.setSecurityClassification(SecurityClassification.valueOf(
          (String) stored.get("security_classification")));
      details.setData(data);
      String supplementary = (String) stored.get("supplementary_data");
      details.setSupplementaryData(supplementary == null ? Map.of()
          : mapper.convertValue(fromJson(supplementary), JSON_NODE_MAP));
      return details;
    }
  }

  public record Audit(long id, String eventId, int version, long revision) {
  }

  public record CaseSnapshot(JsonNode rawData,
                             int blobVersion,
                             long caseRevision,
                             String state,
                             TestClassification classification,
                             Map<String, Object> supplementaryData) {
  }

  public abstract sealed class Submission permits Accepted, Rejected, CreationRejected {
    private final List<String> errors;
    private final List<String> warnings;
    private final String confirmationHeader;
    private final String confirmationBody;

    private Submission(DecentralisedSubmitEventResponse response) {
      this.errors = response.getErrors() == null ? List.of() : List.copyOf(response.getErrors());
      this.warnings = response.getWarnings() == null ? List.of() : List.copyOf(response.getWarnings());
      var details = response.getCaseDetails();
      var confirmation = details == null ? null : details.getCaseDetails().getAfterSubmitCallbackResponse();
      this.confirmationHeader = confirmation == null ? null : confirmation.getConfirmationHeader();
      this.confirmationBody = confirmation == null ? null : confirmation.getConfirmationBody();
    }

    public List<String> errors() {
      return errors;
    }

    public List<String> warnings() {
      return warnings;
    }

    public String confirmationHeader() {
      return confirmationHeader;
    }

    public String confirmationBody() {
      return confirmationBody;
    }
  }

  public final class Accepted extends Submission {
    private final Case projectedCase;
    private final CaseSnapshot snapshot;
    private final Audit audit;

    private Accepted(DecentralisedSubmitEventResponse response,
                     Case projectedCase,
                     CaseSnapshot snapshot,
                     Audit audit) {
      super(response);
      this.projectedCase = projectedCase;
      this.snapshot = snapshot;
      this.audit = audit;
    }

    public Case projectedCase() {
      return projectedCase;
    }

    public JsonNode rawData() {
      return snapshot.rawData();
    }

    public Case storedData() {
      return mapper.convertValue(snapshot.rawData(), caseClass);
    }

    public State state() {
      return Enum.valueOf(stateClass, snapshot.state());
    }

    public TestClassification classification() {
      return snapshot.classification();
    }

    public Map<String, Object> supplementaryData() {
      return snapshot.supplementaryData();
    }

    public int blobVersion() {
      return snapshot.blobVersion();
    }

    public long caseRevision() {
      return snapshot.caseRevision();
    }

    public CaseSnapshot snapshot() {
      return snapshot;
    }

    public Audit audit() {
      return audit;
    }
  }

  public final class Rejected extends Submission {
    private final CaseSnapshot originalSnapshot;

    private Rejected(DecentralisedSubmitEventResponse response, CaseSnapshot originalSnapshot) {
      super(response);
      this.originalSnapshot = Objects.requireNonNull(originalSnapshot);
    }

    public JsonNode rawData() {
      return originalSnapshot.rawData();
    }

    public Case storedData() {
      return mapper.convertValue(originalSnapshot.rawData(), caseClass);
    }

    public State state() {
      return Enum.valueOf(stateClass, originalSnapshot.state());
    }

    public TestClassification classification() {
      return originalSnapshot.classification();
    }

    public Map<String, Object> supplementaryData() {
      return originalSnapshot.supplementaryData();
    }

    public CaseSnapshot snapshot() {
      return originalSnapshot;
    }
  }

  public final class CreationRejected extends Submission {
    private CreationRejected(DecentralisedSubmitEventResponse response) {
      super(response);
    }
  }

  private String json(Object data) {
    try {
      return mapper.writeValueAsString(data);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Cannot serialise case fixture", ex);
    }
  }

  private JsonNode fromJson(String json) {
    try {
      return mapper.readTree(json);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Cannot read stored case data", ex);
    }
  }

  private LocalDateTime dateTime(Object value) {
    return value == null ? null : ((Timestamp) value).toLocalDateTime();
  }
}

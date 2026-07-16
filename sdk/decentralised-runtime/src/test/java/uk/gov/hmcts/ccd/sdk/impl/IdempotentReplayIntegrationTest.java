package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableSet;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.sdk.CaseReindexingService;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedDataConfiguration;

@SpringBootTest(classes = IdempotentReplayIntegrationTest.TestConfig.class, properties = {
    "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///ccd",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
})
class IdempotentReplayIntegrationTest {

  private static final long CASE_REFERENCE = 9999000000000000L;
  private static final long CASE_ID = 123L;
  private static final long CASE_ID_A = 456L;
  private static final long CASE_ID_B = 789L;
  private static final long CASE_REFERENCE_A = 1111000000000000L;
  private static final long CASE_REFERENCE_B = 2222000000000000L;
  private static final long UPSERT_CASE_REFERENCE = 3333000000000000L;
  private static final long REINDEX_CASE_REFERENCE = 4444000000000000L;
  private static final long LOWER_PRIORITY_REINDEX_CASE_REFERENCE = 5555000000000000L;
  private static final long LIVE_UPDATE_AFTER_REINDEX_CASE_REFERENCE = 6666000000000000L;

  @Autowired
  private NamedParameterJdbcTemplate jdbc;

  @Autowired
  private ObjectMapper mapper;

  @Autowired
  private CaseDataRepository repository;

  @Autowired
  private CaseReindexingService reindexingService;

  @Test
  void idempotentReplayReturnsEventVersionAndRevision() {
    seedCaseData(3, 5);
    long eventId = insertEvent(1, 1, UUID.randomUUID());

    DecentralisedCaseDetails replayed = repository.caseDetailsAtEvent(CASE_REFERENCE, eventId);

    assertThat(replayed.getCaseDetails().getVersion()).isEqualTo(1);
    assertThat(replayed.getCaseDetails().getRevision()).isEqualTo(1L);

    DecentralisedCaseDetails latest = repository.getCase(CASE_REFERENCE);
    assertThat(latest.getCaseDetails().getVersion()).isEqualTo(3);
    assertThat(latest.getCaseDetails().getRevision()).isEqualTo(5L);
  }

  @Test
  void getCasesReturnsEmptyListWhenNoReferencesProvided() {
    assertThat(repository.getCases(List.of())).isEmpty();
  }

  @Test
  void getCasesReturnsStableOrderByReferenceAscending() {
    seedCaseData(CASE_ID_A, CASE_REFERENCE_A, 1, 1);
    seedCaseData(CASE_ID_B, CASE_REFERENCE_B, 1, 1);

    var cases = repository.getCases(java.util.List.of(CASE_REFERENCE_B, CASE_REFERENCE_A));

    assertThat(cases)
        .extracting(details -> details.getCaseDetails().getReference())
        .containsExactly(CASE_REFERENCE_A, CASE_REFERENCE_B);
  }

  @Test
  void upsertCaseEnforcesHmctsServiceIdInSupplementaryData() {
    repository.upsertCase(buildEvent(UPSERT_CASE_REFERENCE, "TestCase"), Optional.empty());

    String hmctsServiceId = jdbc.queryForObject(
        "select supplementary_data->>'HMCTSServiceId' from ccd.case_data where reference = :ref",
        Map.of("ref", UPSERT_CASE_REFERENCE),
        String.class
    );

    assertThat(hmctsServiceId).isEqualTo("ABA1");
  }

  @Test
  void upsertCaseLeavesSupplementaryDataEmptyWhenCaseTypeHasNoConfiguredServiceId() {
    long caseRef = UPSERT_CASE_REFERENCE + 1;
    repository.upsertCase(buildEvent(caseRef, "UnknownCaseType"), Optional.empty());

    String supplementaryData = jdbc.queryForObject(
        "select supplementary_data::text from ccd.case_data where reference = :ref",
        Map.of("ref", caseRef),
        String.class
    );

    assertThat(supplementaryData).isEqualTo("{}");
  }

  @Test
  void upsertCasePreservesUnrelatedSupplementaryDataKeys() {
    long caseRef = UPSERT_CASE_REFERENCE + 2;
    seedCaseData(caseRef, caseRef, 1, 1);

    jdbc.update(
        "update ccd.case_data set supplementary_data = :supplementary::jsonb where reference = :ref",
        Map.of(
            "supplementary", "{\"foo\":\"bar\",\"existingNumber\":42}",
            "ref", caseRef
        )
    );

    repository.upsertCase(buildEvent(caseRef, "TestCase"), Optional.empty());

    Map<String, Object> result = jdbc.queryForMap(
        """
            select supplementary_data->>'foo' as foo,
                   supplementary_data->>'existingNumber' as existing_number,
                   supplementary_data->>'HMCTSServiceId' as hmcts_service_id
            from ccd.case_data
            where reference = :ref
            """,
        Map.of("ref", caseRef)
    );

    assertThat(result.get("foo")).isEqualTo("bar");
    assertThat(result.get("existing_number")).isEqualTo("42");
    assertThat(result.get("hmcts_service_id")).isEqualTo("ABA1");
  }

  @Test
  void upsertCaseStoresAuthoritativeTtlAndResolvedTtlInsteadOfHandlerTtl() {
    long caseRef = UPSERT_CASE_REFERENCE + 3;
    ObjectNode ttl = ttl("2030-01-01", "2031-02-03", true);
    DecentralisedCaseEvent event = buildEvent(caseRef, "TestCase");
    event.getCaseDetails().setData(Map.of("TTL", ttl));
    event.setResolvedTtl(LocalDate.of(2031, 2, 3));

    ObjectNode dataUpdate = mapper.createObjectNode();
    dataUpdate.put("subject", "A case");
    dataUpdate.set("TTL", ttl("2099-12-31", null, false));

    repository.upsertCase(event, Optional.of(dataUpdate));

    Map<String, Object> stored = jdbc.queryForMap(
        """
        select data #>> '{TTL,SystemTTL}' as system_ttl,
               data #>> '{TTL,OverrideTTL}' as override_ttl,
               data #>> '{TTL,Suspended}' as ttl_suspended,
               resolved_ttl,
               data->>'subject' as subject,
               version
          from ccd.case_data
         where reference = :reference
        """,
        Map.of("reference", caseRef)
    );

    assertThat(stored.get("system_ttl")).isEqualTo("2030-01-01");
    assertThat(stored.get("override_ttl")).isEqualTo("2031-02-03");
    assertThat(stored.get("ttl_suspended")).isEqualTo("Yes");
    assertThat(stored.get("resolved_ttl")).isEqualTo(java.sql.Date.valueOf("2031-02-03"));
    assertThat(stored.get("subject")).isEqualTo("A case");
    assertThat(stored.get("version")).isEqualTo(1);

    CaseDetails returned = repository.getCase(caseRef).getCaseDetails();
    JsonNode returnedTtl = returned.getData().get("TTL");
    assertThat(returnedTtl.get("SystemTTL").asText()).isEqualTo("2030-01-01");
    assertThat(returnedTtl.get("OverrideTTL").asText()).isEqualTo("2031-02-03");
    assertThat(returnedTtl.get("Suspended").asText()).isEqualTo("Yes");
    assertThat(returned.getResolvedTTL())
        .isEqualTo(LocalDate.of(2031, 2, 3));
  }

  @Test
  void insertWithoutDataUpdateStoresTtlButNotNonReservedIncomingData() {
    long caseRef = UPSERT_CASE_REFERENCE + 30;
    DecentralisedCaseEvent event = buildEvent(caseRef, "TestCase");
    event.getCaseDetails().setData(Map.of(
        "subject", mapper.getNodeFactory().textNode("Submit handler create"),
        "TTL", ttl("2034-01-02", null, false)
    ));
    event.setResolvedTtl(LocalDate.of(2034, 1, 2));

    repository.upsertCase(event, Optional.empty());

    Map<String, Object> stored = jdbc.queryForMap(
        """
        select data->>'subject' as subject,
               data #>> '{TTL,SystemTTL}' as system_ttl,
               resolved_ttl
        from ccd.case_data
        where reference = :reference
        """,
        Map.of("reference", caseRef)
    );
    assertThat(stored.get("subject")).isNull();
    assertThat(stored.get("system_ttl")).isEqualTo("2034-01-02");
    assertThat(stored.get("resolved_ttl")).isEqualTo(java.sql.Date.valueOf("2034-01-02"));
  }

  @Test
  void ttlOnlyUpdateUsesMergeRevisionWithoutAdvancingBlobVersion() {
    long caseRef = UPSERT_CASE_REFERENCE + 4;
    seedCaseData(caseRef, caseRef, 7, 11, "{\"subject\":\"Existing case\"}", null);
    Long currentRevision = caseRevision(caseRef);

    DecentralisedCaseEvent event = buildEvent(caseRef, "TestCase");
    event.getCaseDetails().setVersion(7);
    event.setMergeRevision(currentRevision);
    event.getCaseDetails().setData(Map.of("TTL", ttl("2035-04-05", null, false)));
    event.setResolvedTtl(LocalDate.of(2035, 4, 5));

    repository.upsertCase(event, Optional.empty());

    Map<String, Object> stored = jdbc.queryForMap(
        "select data #>> '{TTL,SystemTTL}' as system_ttl, data->>'subject' as subject, "
            + "resolved_ttl, version, case_revision "
            + "from ccd.case_data where reference = :reference",
        Map.of("reference", caseRef)
    );
    assertThat(stored.get("system_ttl")).isEqualTo("2035-04-05");
    assertThat(stored.get("subject")).isEqualTo("Existing case");
    assertThat(stored.get("resolved_ttl")).isEqualTo(java.sql.Date.valueOf("2035-04-05"));
    assertThat(stored.get("version")).isEqualTo(7);
    assertThat(stored.get("case_revision")).isEqualTo(currentRevision + 1);
  }

  @Test
  void submitHandlerUpdateRemovesTtlWithoutChangingNonReservedDataOrBlobVersion() {
    assertTtlRemoval(UPSERT_CASE_REFERENCE + 31, Optional.empty());
  }

  @Test
  void legacyUpdateRemovesTtlWithoutChangingNonReservedDataOrBlobVersion() {
    ObjectNode handlerData = mapper.createObjectNode()
        .put("subject", "Existing case");
    handlerData.set("TTL", ttl("2099-12-31", null, false));
    assertTtlRemoval(UPSERT_CASE_REFERENCE + 32, Optional.of(handlerData));
  }

  @Test
  void rejectsDifferingTtlWhenMergeRevisionIsStale() {
    long caseRef = UPSERT_CASE_REFERENCE + 5;
    seedCaseData(caseRef, caseRef, 3, 8);

    DecentralisedCaseEvent event = buildEvent(caseRef, "TestCase");
    event.getCaseDetails().setVersion(3);
    event.setMergeRevision(7L);
    event.getCaseDetails().setData(Map.of("TTL", ttl("2040-01-01", null, false)));
    event.setResolvedTtl(LocalDate.of(2040, 1, 1));

    assertThatThrownBy(() -> repository.upsertCase(event, Optional.empty()))
        .isInstanceOf(EmptyResultDataAccessException.class);
  }

  @Test
  void allowsAlreadyEqualTtlWhenMergeRevisionIsStale() {
    long caseRef = UPSERT_CASE_REFERENCE + 6;
    seedCaseData(
        caseRef,
        caseRef,
        3,
        8,
        "{\"TTL\":{\"SystemTTL\":\"2040-01-01\",\"Suspended\":\"No\"}}",
        LocalDate.of(2040, 1, 1)
    );

    DecentralisedCaseEvent event = buildEvent(caseRef, "TestCase");
    event.getCaseDetails().setVersion(3);
    event.setMergeRevision(7L);
    event.getCaseDetails().setData(Map.of("TTL", ttl("2040-01-01", null, false)));
    event.setResolvedTtl(LocalDate.of(2040, 1, 1));

    repository.upsertCase(event, Optional.empty());

    Integer version = jdbc.queryForObject(
        "select version from ccd.case_data where reference = :reference",
        Map.of("reference", caseRef),
        Integer.class
    );
    assertThat(version).isEqualTo(3);
  }

  @Test
  void rejectsDifferingResolvedTtlWhenJsonTtlIsEqualAndMergeRevisionIsStale() {
    long caseRef = UPSERT_CASE_REFERENCE + 7;
    seedCaseData(
        caseRef,
        caseRef,
        3,
        8,
        "{\"TTL\":{\"SystemTTL\":\"2040-01-01\",\"Suspended\":\"No\"}}",
        LocalDate.of(2040, 1, 1)
    );

    DecentralisedCaseEvent event = buildEvent(caseRef, "TestCase");
    event.getCaseDetails().setVersion(3);
    event.setMergeRevision(7L);
    event.getCaseDetails().setData(Map.of("TTL", ttl("2040-01-01", null, false)));
    event.setResolvedTtl(LocalDate.of(2041, 1, 1));

    assertThatThrownBy(() -> repository.upsertCase(event, Optional.empty()))
        .isInstanceOf(EmptyResultDataAccessException.class);
  }

  @Test
  void idempotentReplayKeepsHistoricalTtlRatherThanCurrentTtl() {
    long caseRef = UPSERT_CASE_REFERENCE + 8;
    seedCaseData(
        caseRef,
        caseRef,
        3,
        5,
        "{\"TTL\":{\"SystemTTL\":\"2050-01-01\",\"Suspended\":\"No\"}}",
        LocalDate.of(2050, 1, 1)
    );
    long eventId = insertEvent(
        caseRef,
        2,
        4,
        UUID.randomUUID(),
        "{\"TTL\":{\"SystemTTL\":\"2049-01-01\",\"Suspended\":\"No\"}}"
    );

    JsonNode replayedTtl = repository.caseDetailsAtEvent(caseRef, eventId)
        .getCaseDetails().getData().get("TTL");

    assertThat(replayedTtl.get("SystemTTL").asText()).isEqualTo("2049-01-01");
  }

  @Test
  void reindexingAdvancesExistingQueueRowToLatestRevision() {
    seedCaseData(REINDEX_CASE_REFERENCE, REINDEX_CASE_REFERENCE, 1, 5);
    jdbc.update(
        """
        update ccd.es_queue
           set case_revision = :stale_revision,
               enqueued_at = now() - interval '1 minute'
         where reference = :reference
        """,
        Map.of(
            "reference", REINDEX_CASE_REFERENCE,
            "stale_revision", 2
        )
    );

    reindexingService.enqueueCasesModifiedSince(LocalDate.now().minusDays(1));

    Long queuedRevision = jdbc.queryForObject(
        "select case_revision from ccd.es_queue where reference = :reference",
        Map.of("reference", REINDEX_CASE_REFERENCE),
        Long.class
    );
    assertThat(queuedRevision).isEqualTo(5L);

    Boolean keptLiveQueuePriority = jdbc.queryForObject(
        """
        select enqueued_at < now()
        from ccd.es_queue
        where reference = :reference
        """,
        Map.of("reference", REINDEX_CASE_REFERENCE),
        Boolean.class
    );
    assertThat(keptLiveQueuePriority).isTrue();
  }

  @Test
  void reindexingPlacesNewQueueRowsBehindLiveCaseUpdates() {
    seedCaseData(LOWER_PRIORITY_REINDEX_CASE_REFERENCE, LOWER_PRIORITY_REINDEX_CASE_REFERENCE, 1, 5);
    jdbc.update(
        "delete from ccd.es_queue where reference = :reference",
        Map.of("reference", LOWER_PRIORITY_REINDEX_CASE_REFERENCE)
    );

    reindexingService.enqueueCasesModifiedSince(LocalDate.now().minusDays(1));

    Boolean lowerPriorityByDefault = jdbc.queryForObject(
        """
        select enqueued_at >= now() + interval '5 hours'
        from ccd.es_queue
        where reference = :reference
        """,
        Map.of("reference", LOWER_PRIORITY_REINDEX_CASE_REFERENCE),
        Boolean.class
    );
    assertThat(lowerPriorityByDefault).isTrue();
  }

  @Test
  void liveCaseUpdateRestoresQueuePriorityAfterReindexingQueuedTheCase() {
    seedCaseData(LIVE_UPDATE_AFTER_REINDEX_CASE_REFERENCE, LIVE_UPDATE_AFTER_REINDEX_CASE_REFERENCE, 1, 5);
    jdbc.update(
        "delete from ccd.es_queue where reference = :reference",
        Map.of("reference", LIVE_UPDATE_AFTER_REINDEX_CASE_REFERENCE)
    );

    reindexingService.enqueueCasesModifiedSince(LocalDate.now().minusDays(1));

    Boolean lowerPriorityAfterReindex = jdbc.queryForObject(
        """
        select enqueued_at >= now() + interval '5 hours'
        from ccd.es_queue
        where reference = :reference
        """,
        Map.of("reference", LIVE_UPDATE_AFTER_REINDEX_CASE_REFERENCE),
        Boolean.class
    );
    assertThat(lowerPriorityAfterReindex).isTrue();

    jdbc.update(
        """
        update ccd.case_data
           set case_revision = :revision,
               last_modified = now()
         where reference = :reference
        """,
        Map.of(
            "reference", LIVE_UPDATE_AFTER_REINDEX_CASE_REFERENCE,
            "revision", 6
        )
    );

    Map<String, Object> queued = jdbc.queryForMap(
        """
        select case_revision,
               enqueued_at < now() + interval '1 minute' as live_priority
        from ccd.es_queue
        where reference = :reference
        """,
        Map.of("reference", LIVE_UPDATE_AFTER_REINDEX_CASE_REFERENCE)
    );
    assertThat(queued.get("case_revision")).isEqualTo(6L);
    assertThat(queued.get("live_priority")).isEqualTo(true);
  }

  private void seedCaseData(int version, long revision) {
    seedCaseData(CASE_ID, CASE_REFERENCE, version, revision);
  }

  private void seedCaseData(long caseId, long caseReference, int version, long revision) {
    seedCaseData(caseId, caseReference, version, revision, "{}", null);
  }

  private void seedCaseData(long caseId,
                            long caseReference,
                            int version,
                            long revision,
                            String data,
                            LocalDate resolvedTtl) {
    var params = new MapSqlParameterSource()
        .addValue("id", caseId)
        .addValue("reference", caseReference)
        .addValue("version", version)
        .addValue("revision", revision)
        .addValue("data", data)
        .addValue("resolved_ttl", resolvedTtl);

    jdbc.update(
        """
        insert into ccd.case_data (
          id,
          reference,
          version,
          jurisdiction,
          case_type_id,
          state,
          data,
          resolved_ttl,
          supplementary_data,
          security_classification,
          case_revision,
          created_date,
          last_modified,
          last_state_modified_date
        ) values (
          :id,
          :reference,
          :version,
          'TEST',
          'TestCase',
          'Submitted',
          :data::jsonb,
          :resolved_ttl,
          '{}'::jsonb,
          'PUBLIC',
          :revision,
          now(),
          now(),
          now()
        )
        """,
        params
    );
  }

  private DecentralisedCaseEvent buildEvent(long caseReference, String caseType) {
    var caseDetails = new CaseDetails();
    caseDetails.setReference(caseReference);
    caseDetails.setJurisdiction("TEST");
    caseDetails.setCaseTypeId(caseType);
    caseDetails.setState("Submitted");
    caseDetails.setVersion(1);
    caseDetails.setSecurityClassification(SecurityClassification.PUBLIC);

    return DecentralisedCaseEvent.builder()
        .caseDetails(caseDetails)
        .internalCaseId(caseReference)
        .build();
  }

  private ObjectNode ttl(String systemTtl, String overrideTtl, Boolean suspended) {
    ObjectNode ttl = mapper.createObjectNode();
    if (systemTtl != null) {
      ttl.put("SystemTTL", systemTtl);
    }
    if (overrideTtl != null) {
      ttl.put("OverrideTTL", overrideTtl);
    }
    if (suspended != null) {
      ttl.put("Suspended", suspended ? "Yes" : "No");
    }
    return ttl;
  }

  private Long caseRevision(long caseRef) {
    return jdbc.queryForObject(
        "select case_revision from ccd.case_data where reference = :reference",
        Map.of("reference", caseRef),
        Long.class
    );
  }

  private void assertTtlRemoval(long caseRef, Optional<JsonNode> dataUpdate) {
    seedCaseData(
        caseRef,
        caseRef,
        7,
        11,
        "{\"subject\":\"Existing case\",\"TTL\":{\"SystemTTL\":\"2035-04-05\",\"Suspended\":\"No\"}}",
        LocalDate.of(2035, 4, 5)
    );
    Long currentRevision = caseRevision(caseRef);
    DecentralisedCaseEvent event = buildEvent(caseRef, "TestCase");
    event.getCaseDetails().setVersion(7);
    event.setMergeRevision(currentRevision);
    event.getCaseDetails().setData(Map.of());

    repository.upsertCase(event, dataUpdate);

    Map<String, Object> stored = storedTtlMetadata(caseRef);
    assertThat(stored.get("has_ttl")).isEqualTo(false);
    assertThat(stored.get("subject")).isEqualTo("Existing case");
    assertThat(stored.get("resolved_ttl")).isNull();
    assertThat(stored.get("version")).isEqualTo(7);
    assertThat(stored.get("case_revision")).isEqualTo(currentRevision + 1);
  }

  private Map<String, Object> storedTtlMetadata(long caseRef) {
    return jdbc.queryForMap(
        """
        select jsonb_exists(data, 'TTL') as has_ttl,
               data ->> 'subject' as subject,
               resolved_ttl,
               version,
               case_revision
          from ccd.case_data
         where reference = :reference
        """,
        Map.of("reference", caseRef)
    );
  }

  private long insertEvent(int version, long revision, UUID idempotencyKey) {
    return insertEvent(CASE_ID, version, revision, idempotencyKey, "{}");
  }

  private long insertEvent(long caseId,
                           int version,
                           long revision,
                           UUID idempotencyKey,
                           String data) {
    var params = new MapSqlParameterSource()
        .addValue("case_data_id", caseId)
        .addValue("case_type_version", 1)
        .addValue("event_id", "ev1")
        .addValue("summary", "summary")
        .addValue("description", "description")
        .addValue("user_id", "user-1")
        .addValue("case_type_id", "TestCase")
        .addValue("state_id", "Historical")
        .addValue("data", data)
        .addValue("user_first_name", "Test")
        .addValue("user_last_name", "User")
        .addValue("event_name", "Event One")
        .addValue("state_name", "Historical")
        .addValue("security_classification", "PUBLIC")
        .addValue("version", version)
        .addValue("case_revision", revision)
        .addValue("idempotency_key", idempotencyKey);

    return jdbc.queryForObject(
        """
        insert into ccd.case_event (
          case_data_id,
          case_type_version,
          event_id,
          summary,
          description,
          user_id,
          case_type_id,
          state_id,
          data,
          user_first_name,
          user_last_name,
          event_name,
          state_name,
          security_classification,
          version,
          case_revision,
          idempotency_key
        ) values (
          :case_data_id,
          :case_type_version,
          :event_id,
          :summary,
          :description,
          :user_id,
          :case_type_id,
          :state_id,
          :data::jsonb,
          :user_first_name,
          :user_last_name,
          :event_name,
          :state_name,
          :security_classification::ccd.securityclassification,
          :version,
          :case_revision,
          :idempotency_key
        )
        returning id
        """,
        params,
        Long.class
    );
  }

  @Configuration
  @Import({CaseDataRepository.class, CaseReindexingService.class, DecentralisedDataConfiguration.class})
  @ImportAutoConfiguration({
      DataSourceAutoConfiguration.class,
      JdbcTemplateAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class,
      TransactionAutoConfiguration.class,
      FlywayAutoConfiguration.class
  })
  static class TestConfig {

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    ResolvedConfigRegistry resolvedConfigRegistry() {
      var resolved = new ResolvedCCDConfig<>(
          Object.class,
          TestState.class,
          TestRole.class,
          Map.of(),
          ImmutableSet.copyOf(TestState.values())
      ) {
        @Override
        public String getCaseType() {
          return "TestCase";
        }

        @Override
        public String getHmctsServiceId() {
          return "ABA1";
        }
      };

      return new ResolvedConfigRegistry(List.of(resolved));
    }
  }

  private enum TestState {
    Submitted
  }

  private enum TestRole implements HasRole {
    CASEWORKER;

    @Override
    public String getRole() {
      return "caseworker";
    }

    @Override
    public String getCaseTypePermissions() {
      return "CRUD";
    }
  }
}

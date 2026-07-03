package uk.gov.hmcts.ccd.sdk.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.ccd.sdk.migration.CcdDataMigrationMode.CUTOVER;
import static uk.gov.hmcts.ccd.sdk.migration.CcdDataMigrationMode.PRELOAD_EVENTS;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedDataConfiguration;

@SpringBootTest(classes = CcdDataMigrationTaskIntegrationTest.TestConfig.class, properties = {
    "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///ccd",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
})
class CcdDataMigrationTaskIntegrationTest {
  private static final long CASE_REVISION_OFFSET = 1_000_000_000L;
  private static final String PERF_CASE_TYPE = "PerfCase";

  @Autowired
  private NamedParameterJdbcTemplate jdbc;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Autowired
  private DataSource dataSource;

  @BeforeEach
  void setUp() {
    jdbc.getJdbcTemplate().execute("drop schema if exists fdw_stage cascade");
    jdbc.getJdbcTemplate().execute("drop schema if exists source cascade");
    jdbc.getJdbcTemplate().execute("drop server if exists ccd_migration_test_server cascade");
    jdbc.getJdbcTemplate().execute("delete from ccd.ccd_data_migration_progress");
    jdbc.getJdbcTemplate().execute("truncate table ccd.case_event, ccd.case_data restart identity cascade");
    restoreTargetSchemaState();
    createSourceTables();
    createFdwTables();
  }

  @Test
  void preloadsEventsBySourceHighWaterMarkWithoutDroppingTargetProtections() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertSourceCaseEvent(102, 10, "update", "Updated", "{\"field\":\"two\"}", LocalDateTime.now());

    CcdDataMigrationRunResult result = task(PRELOAD_EVENTS, 10, 10).runMigration();

    assertThat(result.caughtUp()).isTrue();
    assertThat(result.eventsProcessed()).isEqualTo(2);
    assertThat(countRows("ccd.case_data")).isEqualTo(1);
    assertThat(countRows("ccd.case_event")).isEqualTo(2);
    assertThat(progressStatus()).isEqualTo("PRELOAD");
    assertThat(localEventHwm()).isEqualTo(102);
    assertThat(caseEventRevision(101)).isEqualTo(1);
    assertThat(caseEventRevision(102)).isEqualTo(2);
    assertThat(caseRevision(10)).isZero();
    assertElasticsearchQueueEmpty();
    assertThat(caseDataTriggerEnabled("trigger_enqueue_case_revision")).isFalse();
    assertTargetProtectionsPresent();
  }

  @Test
  void cutoverCopiesRemainingEventsRefreshesCaseDataAndMarksComplete() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    task(PRELOAD_EVENTS, 10, 10).runMigration();

    updateSourceCase(10, 2, "Updated", "{\"field\":\"cutover\"}");
    insertSourceCaseEvent(102, 10, "update", "Updated", "{\"field\":\"cutover\"}", LocalDateTime.now());

    CcdDataMigrationRunResult result = task(CUTOVER, 10, 10).runMigration();

    assertThat(result.caughtUp()).isTrue();
    assertThat(progressStatus()).isEqualTo("COMPLETE");
    assertThat(cutoverEventHwm()).isEqualTo(102);
    assertThat(countRows("ccd.case_event")).isEqualTo(2);
    assertThat(targetCaseState(10)).isEqualTo("Updated");
    assertThat(targetCaseData(10)).isEqualTo("{\"field\": \"cutover\"}");
    assertThat(caseEventRevision(101)).isEqualTo(1);
    assertThat(caseEventRevision(102)).isEqualTo(2);
    assertThat(caseRevision(10)).isEqualTo(CASE_REVISION_OFFSET + 2);
    assertThat(caseEventSequenceLastValue()).isGreaterThanOrEqualTo(102);
    assertElasticsearchQueueEmpty();
    assertThat(caseDataTriggerEnabled("trigger_enqueue_case_revision")).isTrue();
    assertTargetProtectionsPresent();
  }

  @Test
  void preloadResumesFromLocalTargetEventHighWaterMark() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertSourceCaseEvent(102, 10, "update", "Updated", "{\"field\":\"two\"}", minutesAgo(60));
    insertTargetCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}", 0);
    insertTargetEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", 1);
    createProgress();

    CcdDataMigrationRunResult result = task(PRELOAD_EVENTS, 10, 10).runMigration();

    assertThat(result.caughtUp()).isTrue();
    assertThat(result.eventsProcessed()).isEqualTo(1);
    assertThat(countRows("ccd.case_event")).isEqualTo(2);
    assertThat(localEventHwm()).isEqualTo(102);
    assertThat(caseEventRevision(102)).isEqualTo(2);
    assertElasticsearchQueueEmpty();
  }

  @Test
  void preloadBatchesByMatchingSourceEventsNotGlobalEventIdWindow() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertSourceCaseEvent(1001, 10, "update", "Updated", "{\"field\":\"two\"}", minutesAgo(60));
    insertSourceCaseEvent(2001, 10, "close", "Closed", "{\"field\":\"three\"}", minutesAgo(60));
    insertSourceCaseEvent(102, 10, "other", "Submitted", "{\"field\":\"other\"}", "OtherCase", minutesAgo(60));

    CcdDataMigrationRunResult result = task(PRELOAD_EVENTS, 2, 1).runMigration();

    assertThat(result.caughtUp()).isFalse();
    assertThat(result.eventsProcessed()).isEqualTo(2);
    assertThat(countRows("ccd.case_event")).isEqualTo(2);
    assertThat(localEventHwm()).isEqualTo(1001);
  }

  @Test
  void cutoverPausesBeforeFinalRefreshWhenRuntimeLimitStopsEventCopying() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertSourceCaseEvent(102, 10, "update", "Updated", "{\"field\":\"two\"}", minutesAgo(60));

    CcdDataMigrationRunResult result = task(CUTOVER, 1, 1).runMigration();

    assertThat(result.caughtUp()).isFalse();
    assertThat(progressStatus()).isEqualTo("CUTOVER");
    assertThat(localEventHwm()).isEqualTo(101);
    assertThat(countRows("ccd.case_event")).isEqualTo(1);
    assertThat(caseRevision(10)).isZero();
    assertElasticsearchQueueEmpty();
    assertThat(caseDataTriggerEnabled("trigger_enqueue_case_revision")).isFalse();
  }

  @Test
  void skipsWhenAnotherInstanceHoldsTheAdvisoryLock() throws Exception {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");

    try (Connection connection = dataSource.getConnection()) {
      acquireTaskLock(connection);

      CcdDataMigrationRunResult result = task(PRELOAD_EVENTS, 10, 10).runMigration();

      assertThat(result.lockAcquired()).isFalse();
      assertThat(countRows("ccd.case_data")).isZero();
      releaseTaskLock(connection);
    }
  }

  @Test
  void failsWithDocsLinkWhenFdwTablesAreMissing() {
    jdbc.getJdbcTemplate().execute("drop schema fdw_stage cascade");

    assertThatThrownBy(() -> task(PRELOAD_EVENTS, 10, 10).runMigration())
        .isInstanceOf(CcdDataMigrationException.class)
        .hasMessageContaining("FDW foreign tables are missing")
        .hasMessageContaining(
            "https://github.com/hmcts/dtsse-ccd-config-generator/blob/master/docs/fdw-data-migration.md"
        );
  }

  @Test
  void failsWhenDecentralisedRuntimeIsEnabled() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    var options = CcdDataMigrationTaskOptions.builder(List.of("TestCase")).build();

    assertThatThrownBy(() -> new CcdDataMigrationTask(jdbc, transactionManager, options, () -> true).runMigration())
        .isInstanceOf(CcdDataMigrationException.class)
        .hasMessageContaining("cannot run while the decentralised runtime is enabled")
        .hasMessageContaining("live case writes for the migrated case types still go to source CCD");

    assertThat(countRows("ccd.case_data")).isZero();
    assertTargetProtectionsPresent();
  }

  @Test
  void migratesSeededDatasetWithinPerfHarnessLimit() {
    int caseCount = Integer.getInteger("ccd.data-migration.perf.cases", 100_000);
    int eventsPerCase = Integer.getInteger("ccd.data-migration.perf.events-per-case", 10);
    int eventBatchSize = Integer.getInteger("ccd.data-migration.perf.event-batch-size", 10_000);
    final Duration maxElapsed = Duration.ofSeconds(Long.getLong("ccd.data-migration.perf.max-seconds", 900L));

    seedSourceDataset(PERF_CASE_TYPE, caseCount, eventsPerCase);

    Instant started = Instant.now();
    CcdDataMigrationRunResult preloadResult = new CcdDataMigrationTask(
        jdbc,
        transactionManager,
        CcdDataMigrationTaskOptions.builder(List.of(PERF_CASE_TYPE))
            .mode(PRELOAD_EVENTS)
            .eventBatchSize(eventBatchSize)
            .build()
    ).runMigration();
    CcdDataMigrationRunResult cutoverResult = new CcdDataMigrationTask(
        jdbc,
        transactionManager,
        CcdDataMigrationTaskOptions.builder(List.of(PERF_CASE_TYPE))
            .mode(CUTOVER)
            .eventBatchSize(eventBatchSize)
            .build()
    ).runMigration();
    final Duration elapsed = Duration.between(started, Instant.now());

    long expectedEvents = (long) caseCount * eventsPerCase;
    assertThat(preloadResult.caughtUp()).isTrue();
    assertThat(cutoverResult.caughtUp()).isTrue();
    assertThat(countRows("ccd.case_data", PERF_CASE_TYPE)).isEqualTo((long) caseCount);
    assertThat(countRows("ccd.case_event", PERF_CASE_TYPE)).isEqualTo(expectedEvents);
    assertThat(progressStatus()).isEqualTo("COMPLETE");
    assertElasticsearchQueueEmpty();
    assertThat(caseDataTriggerEnabled("trigger_enqueue_case_revision")).isTrue();
    assertTargetProtectionsPresent();
    assertThat(elapsed).isLessThan(maxElapsed);
  }

  private CcdDataMigrationTask task(
      CcdDataMigrationMode mode,
      int eventBatchSize,
      int maxBatchesPerRun
  ) {
    var options = CcdDataMigrationTaskOptions.builder(List.of("TestCase"))
        .mode(mode)
        .eventBatchSize(eventBatchSize)
        .maxBatchesPerRun(maxBatchesPerRun)
        .build();
    return new CcdDataMigrationTask(jdbc, transactionManager, options);
  }

  private void createSourceTables() {
    jdbc.getJdbcTemplate().execute("create schema source");
    jdbc.getJdbcTemplate().execute("""
        create table source.case_data (
          reference bigint primary key,
          version integer not null,
          created_date timestamp without time zone not null,
          security_classification ccd.securityclassification not null,
          last_state_modified_date timestamp without time zone,
          resolved_ttl date,
          last_modified timestamp without time zone,
          jurisdiction varchar(255) not null,
          case_type_id varchar(255) not null,
          state varchar(255) not null,
          data jsonb not null,
          supplementary_data jsonb,
          id bigint not null unique
        )
        """);
    jdbc.getJdbcTemplate().execute("""
        create table source.case_event (
          id bigint primary key,
          created_date timestamp without time zone not null,
          security_classification ccd.securityclassification not null,
          case_data_id bigint not null,
          case_type_version integer not null,
          event_id varchar(70) not null,
          summary varchar(1024),
          description varchar(65536),
          user_id varchar(64) not null,
          case_type_id varchar(255) not null,
          state_id varchar(255) not null,
          data jsonb not null,
          user_first_name varchar(255) not null,
          user_last_name varchar(255) not null,
          event_name varchar(30) not null,
          state_name varchar(255) not null,
          proxied_by varchar(64),
          proxied_by_first_name varchar(255),
          proxied_by_last_name varchar(255)
        )
        """);
  }

  private void createFdwTables() {
    jdbc.getJdbcTemplate().execute("create extension if not exists pgcrypto");
    jdbc.getJdbcTemplate().execute("create extension if not exists postgres_fdw");
    jdbc.getJdbcTemplate().execute("create schema fdw_stage");
    jdbc.getJdbcTemplate().execute("""
        create server ccd_migration_test_server
        foreign data wrapper postgres_fdw
        options (host '127.0.0.1', port '5432', dbname 'test')
        """);
    jdbc.getJdbcTemplate().execute("""
        create user mapping for current_user
        server ccd_migration_test_server
        options (user 'test', password 'test')
        """);
    jdbc.getJdbcTemplate().execute("""
        create foreign table fdw_stage.case_data (
          reference bigint,
          version integer,
          created_date timestamp without time zone,
          security_classification ccd.securityclassification,
          last_state_modified_date timestamp without time zone,
          resolved_ttl date,
          last_modified timestamp without time zone,
          jurisdiction varchar(255),
          case_type_id varchar(255),
          state varchar(255),
          data jsonb,
          supplementary_data jsonb,
          id bigint
        )
        server ccd_migration_test_server
        options (schema_name 'source', table_name 'case_data', fetch_size '10000')
        """);
    jdbc.getJdbcTemplate().execute("""
        create foreign table fdw_stage.case_event (
          id bigint,
          created_date timestamp without time zone,
          security_classification ccd.securityclassification,
          case_data_id bigint,
          case_type_version integer,
          event_id varchar(70),
          summary varchar(1024),
          description varchar(65536),
          user_id varchar(64),
          case_type_id varchar(255),
          state_id varchar(255),
          data jsonb,
          user_first_name varchar(255),
          user_last_name varchar(255),
          event_name varchar(30),
          state_name varchar(255),
          proxied_by varchar(64),
          proxied_by_first_name varchar(255),
          proxied_by_last_name varchar(255)
        )
        server ccd_migration_test_server
        options (schema_name 'source', table_name 'case_event', fetch_size '10000')
        """);
  }

  private void restoreTargetSchemaState() {
    jdbc.getJdbcTemplate().execute("""
        create unique index if not exists idx_case_event_case_data_revision_unique
        on ccd.case_event (case_data_id, case_revision)
        """);
    jdbc.getJdbcTemplate().execute("""
        do $$
        begin
          if not exists (
            select 1
            from pg_constraint
            where conname = 'case_event_case_data_id_fkey'
              and conrelid = 'ccd.case_event'::regclass
          ) then
            alter table ccd.case_event
            add constraint case_event_case_data_id_fkey
            foreign key (case_data_id)
            references ccd.case_data(id)
            on delete cascade;
          end if;
        end $$;
        """);
    jdbc.getJdbcTemplate().execute("alter table ccd.case_event enable trigger user");
    jdbc.getJdbcTemplate().execute("alter table ccd.case_data enable trigger trigger_increment_case_revision");
    jdbc.getJdbcTemplate().execute("alter table ccd.case_data enable trigger trigger_enqueue_case_revision");
  }

  private void insertSourceCase(long id, long reference, int version, String state, String data) {
    var params = new MapSqlParameterSource()
        .addValue("id", id)
        .addValue("reference", reference)
        .addValue("version", version)
        .addValue("state", state)
        .addValue("data", data);
    jdbc.update(
        """
        insert into source.case_data (
          reference,
          version,
          created_date,
          security_classification,
          last_state_modified_date,
          resolved_ttl,
          last_modified,
          jurisdiction,
          case_type_id,
          state,
          data,
          supplementary_data,
          id
        ) values (
          :reference,
          :version,
          timestamp '2024-01-01 00:00:00',
          'PUBLIC',
          timestamp '2024-01-01 00:00:00',
          null,
          timestamp '2024-01-01 00:00:00',
          'TEST',
          'TestCase',
          :state,
          :data::jsonb,
          '{}'::jsonb,
          :id
        )
        """,
        params
    );
  }

  private void insertSourceCaseEvent(
      long id,
      long caseDataId,
      String eventId,
      String stateId,
      String data,
      LocalDateTime createdDate
  ) {
    insertSourceCaseEvent(id, caseDataId, eventId, stateId, data, "TestCase", createdDate);
  }

  private void insertSourceCaseEvent(
      long id,
      long caseDataId,
      String eventId,
      String stateId,
      String data,
      String caseTypeId,
      LocalDateTime createdDate
  ) {
    var params = new MapSqlParameterSource()
        .addValue("id", id)
        .addValue("caseDataId", caseDataId)
        .addValue("eventId", eventId)
        .addValue("stateId", stateId)
        .addValue("data", data)
        .addValue("caseTypeId", caseTypeId)
        .addValue("createdDate", createdDate);
    jdbc.update(
        """
        insert into source.case_event (
          id,
          created_date,
          security_classification,
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
          proxied_by,
          proxied_by_first_name,
          proxied_by_last_name
        ) values (
          :id,
          :createdDate,
          'PUBLIC',
          :caseDataId,
          1,
          :eventId,
          'summary',
          'description',
          'user-1',
          :caseTypeId,
          :stateId,
          :data::jsonb,
          'Test',
          'User',
          :eventId,
          :stateId,
          null,
          null,
          null
        )
        """,
        params
    );
  }

  private void updateSourceCase(long id, int version, String state, String data) {
    jdbc.update(
        """
        update source.case_data
        set version = :version,
            state = :state,
            data = :data::jsonb,
            last_modified = now() at time zone 'UTC'
        where id = :id
        """,
        Map.of("id", id, "version", version, "state", state, "data", data)
    );
  }

  private void insertTargetCase(long id, long reference, int version, String state, String data, long caseRevision) {
    jdbc.update(
        """
        insert into ccd.case_data (
          reference,
          version,
          created_date,
          security_classification,
          last_state_modified_date,
          resolved_ttl,
          last_modified,
          jurisdiction,
          case_type_id,
          state,
          data,
          supplementary_data,
          id,
          case_revision
        ) values (
          :reference,
          :version,
          timestamp '2024-01-01 00:00:00',
          'PUBLIC',
          timestamp '2024-01-01 00:00:00',
          null,
          timestamp '2024-01-01 00:00:00',
          'TEST',
          'TestCase',
          :state,
          :data::jsonb,
          '{}'::jsonb,
          :id,
          :caseRevision
        )
        """,
        new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("reference", reference)
            .addValue("version", version)
            .addValue("state", state)
            .addValue("data", data)
            .addValue("caseRevision", caseRevision)
    );
  }

  private void insertTargetEvent(
      long id,
      long caseDataId,
      String eventId,
      String stateId,
      String data,
      long caseRevision
  ) {
    jdbc.update(
        """
        insert into ccd.case_event (
          id,
          created_date,
          security_classification,
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
          proxied_by,
          proxied_by_first_name,
          proxied_by_last_name,
          idempotency_key,
          version,
          case_revision
        ) values (
          :id,
          timestamp '2024-01-01 00:00:00',
          'PUBLIC',
          :caseDataId,
          1,
          :eventId,
          'summary',
          'description',
          'user-1',
          'TestCase',
          :stateId,
          :data::jsonb,
          'Test',
          'User',
          :eventId,
          :stateId,
          null,
          null,
          null,
          gen_random_uuid(),
          :caseRevision,
          :caseRevision
        )
        """,
        new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("caseDataId", caseDataId)
            .addValue("eventId", eventId)
            .addValue("stateId", stateId)
            .addValue("data", data)
            .addValue("caseRevision", caseRevision)
    );
  }

  private void createProgress() {
    jdbc.update(
        """
        insert into ccd.ccd_data_migration_progress (
          task_name,
          config_hash
        ) values (
          :taskName,
          :configHash
        )
        """,
        new MapSqlParameterSource()
            .addValue("taskName", "ccd-data-migration")
            .addValue("configHash", CcdDataMigrationTaskOptions.builder(List.of("TestCase"))
                .build()
                .migrationConfigHash())
    );
  }

  private void seedSourceDataset(String caseTypeId, int caseCount, int eventsPerCase) {
    var params = new MapSqlParameterSource()
        .addValue("caseTypeId", caseTypeId)
        .addValue("caseCount", caseCount)
        .addValue("eventsPerCase", eventsPerCase);

    jdbc.update(
        """
        insert into source.case_data (
          reference,
          version,
          created_date,
          security_classification,
          last_state_modified_date,
          resolved_ttl,
          last_modified,
          jurisdiction,
          case_type_id,
          state,
          data,
          supplementary_data,
          id
        )
        select
          9000000000000000::bigint + case_number,
          events_per_case,
          timestamp '2024-01-01 00:00:00' + make_interval(secs => case_number),
          'PUBLIC'::ccd.securityclassification,
          timestamp '2024-01-01 00:00:00' + make_interval(secs => case_number),
          null,
          timestamp '2024-01-01 00:00:00' + make_interval(secs => case_number),
          'TEST',
          :caseTypeId,
          'Submitted',
          jsonb_build_object('caseNumber', case_number),
          '{}'::jsonb,
          9000000000000000::bigint + case_number
        from generate_series(1, :caseCount) case_number
        cross join (select :eventsPerCase::integer as events_per_case) event_counts
        """,
        params
    );

    jdbc.update(
        """
        insert into source.case_event (
          id,
          created_date,
          security_classification,
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
          proxied_by,
          proxied_by_first_name,
          proxied_by_last_name
        )
        select
          9000000000000000::bigint + ((case_number - 1) * :eventsPerCase) + event_number,
          timestamp '2024-01-01 00:00:00' + make_interval(secs => ((case_number - 1) * :eventsPerCase)
              + event_number),
          'PUBLIC'::ccd.securityclassification,
          9000000000000000::bigint + case_number,
          1,
          'event-' || event_number,
          'summary',
          'description',
          'user-1',
          :caseTypeId,
          'Submitted',
          jsonb_build_object('caseNumber', case_number, 'eventNumber', event_number),
          'Test',
          'User',
          'event-' || event_number,
          'Submitted',
          null,
          null,
          null
        from generate_series(1, :caseCount) case_number
        cross join generate_series(1, :eventsPerCase) event_number
        """,
        params
    );
  }

  private long countRows(String tableName) {
    return jdbc.getJdbcTemplate().queryForObject("select count(*) from " + tableName, Long.class);
  }

  private long countRows(String tableName, String caseTypeId) {
    return jdbc.queryForObject(
        "select count(*) from " + tableName + " where case_type_id = :caseTypeId",
        Map.of("caseTypeId", caseTypeId),
        Long.class
    );
  }

  private long caseRevision(long id) {
    return jdbc.queryForObject(
        "select case_revision from ccd.case_data where id = :id",
        Map.of("id", id),
        Long.class
    );
  }

  private long caseEventRevision(long id) {
    return jdbc.queryForObject(
        "select case_revision from ccd.case_event where id = :id",
        Map.of("id", id),
        Long.class
    );
  }

  private String targetCaseState(long id) {
    return jdbc.queryForObject("select state from ccd.case_data where id = :id", Map.of("id", id), String.class);
  }

  private String targetCaseData(long id) {
    return jdbc.queryForObject("select data::text from ccd.case_data where id = :id", Map.of("id", id), String.class);
  }

  private String progressStatus() {
    return jdbc.queryForObject(
        "select status from ccd.ccd_data_migration_progress where task_name = :taskName",
        Map.of("taskName", "ccd-data-migration"),
        String.class
    );
  }

  private long localEventHwm() {
    return jdbc.queryForObject(
        """
        select coalesce(max(ce.id), 0)
        from ccd.case_event ce
        join ccd.case_data cd on cd.id = ce.case_data_id
        where cd.case_type_id = :caseTypeId
        """,
        Map.of("caseTypeId", "TestCase"),
        Long.class
    );
  }

  private long cutoverEventHwm() {
    return jdbc.queryForObject(
        "select cutover_event_hwm from ccd.ccd_data_migration_progress where task_name = :taskName",
        Map.of("taskName", "ccd-data-migration"),
        Long.class
    );
  }

  private long caseEventSequenceLastValue() {
    return jdbc.getJdbcTemplate().queryForObject("select last_value from ccd.case_event_id_seq", Long.class);
  }

  private boolean caseEventCaseDataForeignKeyExists() {
    return Boolean.TRUE.equals(jdbc.queryForObject(
        """
        select exists (
          select 1
          from pg_constraint
          where conname = 'case_event_case_data_id_fkey'
            and conrelid = 'ccd.case_event'::regclass
        )
        """,
        Map.of(),
        Boolean.class
    ));
  }

  private boolean caseEventRevisionIndexExists() {
    return Boolean.TRUE.equals(jdbc.queryForObject(
        """
        select exists (
          select 1
          from pg_class c
          join pg_namespace n on n.oid = c.relnamespace
          where n.nspname = 'ccd'
            and c.relname = 'idx_case_event_case_data_revision_unique'
        )
        """,
        Map.of(),
        Boolean.class
    ));
  }

  private boolean caseEventUserTriggersEnabled() {
    return Boolean.TRUE.equals(jdbc.queryForObject(
        """
        select not exists (
          select 1
          from pg_trigger
          where tgrelid = 'ccd.case_event'::regclass
            and not tgisinternal
            and tgenabled = 'D'
        )
        """,
        Map.of(),
        Boolean.class
    ));
  }

  private boolean caseDataTriggerEnabled(String triggerName) {
    return Boolean.TRUE.equals(jdbc.queryForObject(
        """
        select tgenabled <> 'D'
        from pg_trigger
        where tgrelid = 'ccd.case_data'::regclass
          and tgname = :triggerName
        """,
        Map.of("triggerName", triggerName),
        Boolean.class
    ));
  }

  private void assertElasticsearchQueueEmpty() {
    assertThat(countRows("ccd.es_queue")).isZero();
  }

  private void assertTargetProtectionsPresent() {
    assertThat(caseEventCaseDataForeignKeyExists()).isTrue();
    assertThat(caseEventRevisionIndexExists()).isTrue();
    assertThat(caseEventUserTriggersEnabled()).isTrue();
  }

  private void acquireTaskLock(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("""
          select pg_advisory_lock(hashtext('ccd-data-migration'), hashtext('TestCase'))
          """);
    }
  }

  private void releaseTaskLock(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("""
          select pg_advisory_unlock(hashtext('ccd-data-migration'), hashtext('TestCase'))
          """);
    }
  }

  private LocalDateTime minutesAgo(int minutes) {
    return LocalDateTime.now().minusMinutes(minutes);
  }

  @Configuration
  @Import(DecentralisedDataConfiguration.class)
  @ImportAutoConfiguration({
      DataSourceAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class,
      JdbcTemplateAutoConfiguration.class,
      TransactionAutoConfiguration.class,
      FlywayAutoConfiguration.class
  })
  static class TestConfig {
  }
}

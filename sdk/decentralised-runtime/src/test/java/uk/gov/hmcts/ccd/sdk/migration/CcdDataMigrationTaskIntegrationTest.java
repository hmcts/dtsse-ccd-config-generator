package uk.gov.hmcts.ccd.sdk.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
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
  void migratesInitialLoadInChunksThenCatchesUpDeltaChanges() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(102, 10, "update", "Updated", "{\"field\":\"one-updated\"}");
    insertSourceCase(20, 1000000000000020L, 1, "Submitted", "{\"field\":\"two\"}");
    insertSourceCaseEvent(201, 20, "create", "Submitted", "{\"field\":\"two\"}");

    CcdDataMigrationTask task = task(1, 1);

    CcdDataMigrationRunResult firstRun = task.runMigration();
    assertThat(firstRun.batchesProcessed()).isEqualTo(1);
    assertThat(countRows("ccd.case_data")).isEqualTo(1);
    assertThat(countRows("ccd.case_event")).isEqualTo(2);
    assertThat(caseRevision(10)).isEqualTo(CASE_REVISION_OFFSET + 2);
    assertThat(targetPrepared()).isTrue();
    assertThat(caseEventCaseDataForeignKeyExists()).isFalse();
    assertThat(caseEventRevisionIndexExists()).isFalse();

    CcdDataMigrationRunResult secondRun = task.runMigration();
    assertThat(secondRun.batchesProcessed()).isEqualTo(1);
    assertThat(countRows("ccd.case_data")).isEqualTo(2);
    assertThat(countRows("ccd.case_event")).isEqualTo(3);
    assertThat(caseRevision(20)).isEqualTo(CASE_REVISION_OFFSET + 1);

    task.runMigration();
    CcdDataMigrationRunResult caughtUpRun = task.runMigration();
    assertThat(caughtUpRun.caughtUp()).isTrue();
    assertThat(targetPrepared()).isFalse();
    assertThat(caseEventCaseDataForeignKeyExists()).isTrue();
    assertThat(caseEventRevisionIndexExists()).isTrue();
    assertThat(caseEventUserTriggersEnabled()).isTrue();

    sleepPastProgressWindow();
    jdbc.update(
        """
        update source.case_data
        set version = 2,
            state = 'Updated',
            data = :data::jsonb,
            last_modified = now() at time zone 'UTC'
        where id = 10
        """,
        Map.of("data", "{\"field\":\"delta\"}")
    );
    insertSourceCaseEvent(103, 10, "delta", "Updated", "{\"field\":\"delta\"}");

    CcdDataMigrationRunResult deltaRun = task.runMigration();

    assertThat(deltaRun.batchesProcessed()).isEqualTo(1);
    assertThat(countRows("ccd.case_data")).isEqualTo(2);
    assertThat(countRows("ccd.case_event")).isEqualTo(4);
    assertThat(caseRevision(10)).isEqualTo(CASE_REVISION_OFFSET + 3);
    assertThat(targetCaseState(10)).isEqualTo("Updated");
    assertThat(targetCaseData(10)).isEqualTo("{\"field\": \"delta\"}");
  }

  @Test
  void copiesEventsBySelectedParentCaseIdsWhenEventCaseTypeIsInconsistent() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", "OtherCase");

    CcdDataMigrationRunResult result = task(10, 10).runMigration();

    assertThat(result.batchesProcessed()).isEqualTo(1);
    assertThat(countRows("ccd.case_data")).isEqualTo(1);
    assertThat(countRows("ccd.case_event")).isEqualTo(1);
    assertThat(caseRevision(10)).isEqualTo(CASE_REVISION_OFFSET + 1);
    assertThat(targetCaseEventCaseType(101)).isEqualTo("OtherCase");
  }

  @Test
  void generatesTargetIdempotencyKeyAndPreservesExistingEventOnRetry() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}");

    CcdDataMigrationTask task = task(10, 10);
    task.runMigration();
    final String firstIdempotencyKey = targetCaseEventIdempotencyKey(101);

    sleepPastProgressWindow();
    updateSourceCase(10, 2, "Updated", "{\"field\":\"updated\"}");
    CcdDataMigrationRunResult retryRun = task.runMigration();

    assertThat(retryRun.batchesProcessed()).isEqualTo(1);
    assertThat(countRows("ccd.case_event")).isEqualTo(1);
    assertThat(targetCaseEventIdempotencyKey(101)).isEqualTo(firstIdempotencyKey);
  }

  @Test
  void failsWhenExistingTargetCaseEventDiffersFromSource() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}");

    CcdDataMigrationTask task = task(10, 10);
    task.runMigration();
    jdbc.update(
        "update ccd.case_event set summary = 'target-only-change' where id = :id",
        Map.of("id", 101)
    );

    sleepPastProgressWindow();
    updateSourceCase(10, 2, "Updated", "{\"field\":\"updated\"}");

    assertThatThrownBy(task::runMigration)
        .isInstanceOf(CcdDataMigrationException.class)
        .hasMessageContaining("conflicting case_event rows")
        .hasMessageContaining("101");
  }

  @Test
  void skipsWhenAnotherInstanceHoldsTheAdvisoryLock() throws Exception {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");

    try (Connection connection = dataSource.getConnection()) {
      acquireTaskLock(connection);

      CcdDataMigrationRunResult result = task(10, 10).runMigration();

      assertThat(result.lockAcquired()).isFalse();
      assertThat(countRows("ccd.case_data")).isZero();
      releaseTaskLock(connection);
    }
  }

  @Test
  void stopsAfterCompletedChunkWhenMaxRunTimeIsExceeded() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCase(20, 1000000000000020L, 1, "Submitted", "{\"field\":\"two\"}");

    var options = CcdDataMigrationTaskOptions.builder(List.of("TestCase"))
        .batchSize(1)
        .maxBatchesPerRun(10)
        .deltaOverlap(Duration.ZERO)
        .maxRunTime(Duration.ofNanos(1))
        .build();

    CcdDataMigrationRunResult result = new TestMigrationTask(jdbc, transactionManager, options).runMigration();

    assertThat(result.batchesProcessed()).isEqualTo(1);
    assertThat(result.stoppedByTimeLimit()).isTrue();
    assertThat(countRows("ccd.case_data")).isEqualTo(1);
    assertThat(targetPrepared()).isTrue();
    assertThat(caseEventCaseDataForeignKeyExists()).isFalse();
    assertThat(caseEventRevisionIndexExists()).isFalse();
  }

  @Test
  void resumesWhenOnlyRuntimeLimitsChange() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCase(20, 1000000000000020L, 1, "Submitted", "{\"field\":\"two\"}");

    var firstRunOptions = CcdDataMigrationTaskOptions.builder(List.of("TestCase"))
        .batchSize(1)
        .maxBatchesPerRun(1)
        .deltaOverlap(Duration.ZERO)
        .build();
    new TestMigrationTask(jdbc, transactionManager, firstRunOptions).runMigration();

    var resumedOptions = CcdDataMigrationTaskOptions.builder(List.of("TestCase"))
        .batchSize(10)
        .maxBatchesPerRun(10)
        .maxRunTime(Duration.ofHours(1))
        .deltaOverlap(Duration.ZERO)
        .build();
    CcdDataMigrationRunResult resumedRun = new TestMigrationTask(jdbc, transactionManager, resumedOptions)
        .runMigration();

    assertThat(resumedRun.batchesProcessed()).isEqualTo(1);
    assertThat(countRows("ccd.case_data")).isEqualTo(2);
  }

  @Test
  void failsWhenExistingProgressWasCreatedForDifferentMigrationConfiguration() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    task(1, 1).runMigration();

    var differentCaseTypeOptions = CcdDataMigrationTaskOptions.builder(List.of("OtherCase"))
        .taskName("ccd-data-migration")
        .deltaOverlap(Duration.ZERO)
        .build();

    assertThatThrownBy(() -> new TestMigrationTask(jdbc, transactionManager, differentCaseTypeOptions).runMigration())
        .isInstanceOf(CcdDataMigrationException.class)
        .hasMessageContaining("different migration configuration")
        .hasMessageContaining("Existing configuration")
        .hasMessageContaining("Current configuration")
        .hasMessageContaining("Use a new taskName");
  }

  @Test
  void doesNotPrepareTargetWhenThereIsNoDataToMigrate() {
    CcdDataMigrationRunResult result = task(10, 10).runMigration();

    assertThat(result.caughtUp()).isTrue();
    assertThat(targetPrepared()).isFalse();
    assertThat(caseEventCaseDataForeignKeyExists()).isTrue();
    assertThat(caseEventRevisionIndexExists()).isTrue();
    assertThat(caseEventUserTriggersEnabled()).isTrue();
  }

  @Test
  void deltaCursorDoesNotMissLowerIdCaseUpdatedAfterHigherIdCaseInSameWindow() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCase(20, 1000000000000020L, 1, "Submitted", "{\"field\":\"two\"}");

    CcdDataMigrationTask fullRunTask = task(10, 10);
    fullRunTask.runMigration();
    CcdDataMigrationRunResult caughtUpRun = fullRunTask.runMigration();
    assertThat(caughtUpRun.caughtUp()).isTrue();

    sleepPastProgressWindow();
    updateSourceCase(20, 2, "UpdatedHigherId", "{\"field\":\"two-delta\"}");
    sleepPastProgressWindow();
    updateSourceCase(10, 2, "UpdatedLowerId", "{\"field\":\"one-delta\"}");

    CcdDataMigrationTask oneChunkTask = task(1, 1);

    CcdDataMigrationRunResult firstDeltaRun = oneChunkTask.runMigration();
    assertThat(firstDeltaRun.batchesProcessed()).isEqualTo(1);
    assertThat(targetCaseState(20)).isEqualTo("UpdatedHigherId");

    CcdDataMigrationRunResult secondDeltaRun = oneChunkTask.runMigration();
    assertThat(secondDeltaRun.batchesProcessed()).isEqualTo(1);
    assertThat(targetCaseState(10)).isEqualTo("UpdatedLowerId");
    assertThat(targetCaseData(10)).isEqualTo("{\"field\": \"one-delta\"}");
  }

  @Test
  void deltaOverlapPicksUpLateCommitWithTimestampBeforeLastClosedWindow() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");

    CcdDataMigrationTask initialTask = task(10, 10);
    CcdDataMigrationRunResult initialRun = initialTask.runMigration();
    assertThat(initialRun.caughtUp()).isTrue();
    assertThat(targetCaseState(10)).isEqualTo("Submitted");

    LocalDateTime closedWindowStart = jdbc.queryForObject(
        """
        select window_start
        from ccd.ccd_data_migration_progress
        where task_name = :taskName
        """,
        Map.of("taskName", "ccd-data-migration"),
        LocalDateTime.class
    );
    updateSourceCase(
        10,
        2,
        "LateCommit",
        "{\"field\":\"late\"}",
        closedWindowStart.minusMinutes(5)
    );

    var options = CcdDataMigrationTaskOptions.builder(List.of("TestCase"))
        .batchSize(10)
        .maxBatchesPerRun(10)
        .deltaOverlap(Duration.ofMinutes(10))
        .build();
    CcdDataMigrationRunResult overlapRun = new TestMigrationTask(jdbc, transactionManager, options).runMigration();

    assertThat(overlapRun.batchesProcessed()).isEqualTo(1);
    assertThat(targetCaseState(10)).isEqualTo("LateCommit");
    assertThat(targetCaseData(10)).isEqualTo("{\"field\": \"late\"}");
  }

  @Test
  void skipsFullValidationWhenValidationModeIsNever() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}");

    var initialOptions = CcdDataMigrationTaskOptions.builder(List.of("TestCase"))
        .deltaOverlap(Duration.ZERO)
        .build();
    new TestMigrationTask(jdbc, transactionManager, initialOptions).runMigration();

    moveSourceCaseOutsideNextDeltaWindow(10);
    corruptTargetCaseRevision(10);

    var skipValidationOptions = CcdDataMigrationTaskOptions.builder(List.of("TestCase"))
        .deltaOverlap(Duration.ZERO)
        .validationMode(CcdDataMigrationValidationMode.NEVER)
        .build();

    CcdDataMigrationRunResult result = new TestMigrationTask(jdbc, transactionManager, skipValidationOptions)
        .runMigration();

    assertThat(result.caughtUp()).isTrue();
    assertThat(caseRevision(10)).isZero();
  }

  @Test
  void runsFullValidationWhenValidationModeIsDeltaOnlyAndProgressIsInDeltaPhase() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}");

    var options = CcdDataMigrationTaskOptions.builder(List.of("TestCase"))
        .deltaOverlap(Duration.ZERO)
        .validationMode(CcdDataMigrationValidationMode.DELTA_ONLY)
        .build();
    new TestMigrationTask(jdbc, transactionManager, options).runMigration();

    moveSourceCaseOutsideNextDeltaWindow(10);
    corruptTargetCaseRevision(10);

    assertThatThrownBy(() -> new TestMigrationTask(jdbc, transactionManager, options).runMigration())
        .isInstanceOf(CcdDataMigrationException.class)
        .hasMessageContaining("case_data.case_revision mismatches");
  }

  @Test
  void failsWithDocsLinkWhenFdwTablesAreMissing() {
    jdbc.getJdbcTemplate().execute("drop schema fdw_stage cascade");

    assertThatThrownBy(() -> task(10, 10).runMigration())
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

    assertThatThrownBy(() -> new TestMigrationTask(jdbc, transactionManager, options, () -> true).runMigration())
        .isInstanceOf(CcdDataMigrationException.class)
        .hasMessageContaining("cannot run while the decentralised runtime is enabled")
        .hasMessageContaining("live case writes for the migrated case types still go to source CCD");

    assertThat(countRows("ccd.case_data")).isZero();
    assertThat(caseEventCaseDataForeignKeyExists()).isTrue();
    assertThat(caseEventRevisionIndexExists()).isTrue();
    assertThat(caseEventUserTriggersEnabled()).isTrue();
  }

  @Test
  void canReportCaughtUpWhenSourceEventArrivesWithoutCaseDataModification() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}");

    CcdDataMigrationTask task = task(10, 10);
    assertThat(task.runMigration().caughtUp()).isTrue();

    insertSourceCaseEvent(102, 10, "late-event", "Submitted", "{\"field\":\"late-event\"}");
    sleepPastProgressWindow();

    CcdDataMigrationRunResult result = task.runMigration();

    assertThat(result.caughtUp()).isTrue();
    assertThat(countRows("source.case_event")).isEqualTo(2);
    assertThat(countRows("ccd.case_event")).isEqualTo(1);
  }

  @Test
  void prepareFailureAfterDroppingProtectionsLeavesTargetWithoutForeignKeyOrRevisionIndex() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    createFailingAlterTableEventTriggerAfterFirstAlterTableCommand();

    try {
      assertThatThrownBy(() -> task(10, 10).runMigration())
          .hasMessageContaining("forced prepare failure");

      assertThat(caseEventCaseDataForeignKeyExists()).isFalse();
      assertThat(caseEventRevisionIndexExists()).isFalse();
      assertThat(targetPreparedProgressRows()).isZero();
    } finally {
      dropFailingAlterTableEventTrigger();
      restoreTargetSchemaState();
    }
  }

  @Test
  void migratesSeededDatasetWithinPerfHarnessLimit() {
    int caseCount = Integer.getInteger("ccd.data-migration.perf.cases", 100_000);
    int eventsPerCase = Integer.getInteger("ccd.data-migration.perf.events-per-case", 10);
    int batchSize = Integer.getInteger("ccd.data-migration.perf.batch-size", 250);
    final Duration maxElapsed = Duration.ofSeconds(Long.getLong("ccd.data-migration.perf.max-seconds", 900L));

    seedSourceDataset(PERF_CASE_TYPE, caseCount, eventsPerCase);

    var options = CcdDataMigrationTaskOptions.builder(List.of(PERF_CASE_TYPE))
        .batchSize(batchSize)
        .deltaOverlap(Duration.ZERO)
        .validationMode(CcdDataMigrationValidationMode.ALWAYS)
        .build();

    Instant started = Instant.now();
    CcdDataMigrationRunResult result = new TestMigrationTask(jdbc, transactionManager, options).runMigration();
    Duration elapsed = Duration.between(started, Instant.now());

    long expectedEvents = (long) caseCount * eventsPerCase;
    assertThat(result.caughtUp()).isTrue();
    assertThat(result.batchesProcessed()).isEqualTo((caseCount + batchSize - 1L) / batchSize);
    assertThat(countRows("ccd.case_data", PERF_CASE_TYPE)).isEqualTo((long) caseCount);
    assertThat(countRows("ccd.case_event", PERF_CASE_TYPE)).isEqualTo(expectedEvents);
    assertThat(targetPrepared()).isFalse();
    assertThat(caseEventCaseDataForeignKeyExists()).isTrue();
    assertThat(caseEventRevisionIndexExists()).isTrue();
    assertThat(elapsed).isLessThan(maxElapsed);
  }

  private CcdDataMigrationTask task(int batchSize, int maxBatchesPerRun) {
    var options = CcdDataMigrationTaskOptions.builder(List.of("TestCase"))
        .batchSize(batchSize)
        .maxBatchesPerRun(maxBatchesPerRun)
        .deltaOverlap(Duration.ZERO)
        .build();
    return new TestMigrationTask(jdbc, transactionManager, options);
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
        options (schema_name 'source', table_name 'case_data')
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
        options (schema_name 'source', table_name 'case_event')
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
  }

  private void createFailingAlterTableEventTriggerAfterFirstAlterTableCommand() {
    jdbc.getJdbcTemplate().execute("""
        create table public.ccd_migration_prepare_failure_guard (
          alter_table_count integer not null
        )
        """);
    jdbc.getJdbcTemplate().execute("insert into public.ccd_migration_prepare_failure_guard values (0)");
    jdbc.getJdbcTemplate().execute("""
        create or replace function public.fail_second_alter_table_for_migration_prepare()
        returns event_trigger
        language plpgsql
        as $$
        declare
          command record;
          command_count integer;
        begin
          for command in select * from pg_event_trigger_ddl_commands() loop
            if command.command_tag = 'ALTER TABLE' and command.object_identity = 'ccd.case_event' then
              update public.ccd_migration_prepare_failure_guard
              set alter_table_count = alter_table_count + 1
              returning alter_table_count into command_count;

              if command_count > 1 then
                raise exception 'forced prepare failure';
              end if;
            end if;
          end loop;
        end;
        $$;
        """);
    jdbc.getJdbcTemplate().execute("""
        create event trigger fail_second_alter_table_for_migration_prepare
        on ddl_command_end
        execute function public.fail_second_alter_table_for_migration_prepare()
        """);
  }

  private void dropFailingAlterTableEventTrigger() {
    jdbc.getJdbcTemplate().execute("drop event trigger if exists fail_second_alter_table_for_migration_prepare");
    jdbc.getJdbcTemplate().execute("drop function if exists public.fail_second_alter_table_for_migration_prepare()");
    jdbc.getJdbcTemplate().execute("drop table if exists public.ccd_migration_prepare_failure_guard");
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
          now() at time zone 'UTC',
          'PUBLIC',
          now() at time zone 'UTC',
          null,
          now() at time zone 'UTC',
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

  private void insertSourceCaseEvent(long id, long caseDataId, String eventId, String stateId, String data) {
    insertSourceCaseEvent(id, caseDataId, eventId, stateId, data, "TestCase");
  }

  private void insertSourceCaseEvent(
      long id,
      long caseDataId,
      String eventId,
      String stateId,
      String data,
      String caseTypeId
  ) {
    var params = new MapSqlParameterSource()
        .addValue("id", id)
        .addValue("caseDataId", caseDataId)
        .addValue("eventId", eventId)
        .addValue("stateId", stateId)
        .addValue("data", data)
        .addValue("caseTypeId", caseTypeId);
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
          now() at time zone 'UTC',
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
    updateSourceCase(id, version, state, data, null);
  }

  private void updateSourceCase(long id, int version, String state, String data, LocalDateTime lastModified) {
    var params = new MapSqlParameterSource()
        .addValue("id", id)
        .addValue("version", version)
        .addValue("state", state)
        .addValue("data", data)
        .addValue("lastModified", lastModified, Types.TIMESTAMP);

    jdbc.update(
        """
        update source.case_data
        set version = :version,
            state = :state,
            data = :data::jsonb,
            last_modified = coalesce(:lastModified, now() at time zone 'UTC')
        where id = :id
        """,
        params
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

  private void corruptTargetCaseRevision(long id) {
    jdbc.getJdbcTemplate().execute("alter table ccd.case_data disable trigger user");
    try {
      jdbc.update(
          "update ccd.case_data set case_revision = 0 where id = :id",
          Map.of("id", id)
      );
    } finally {
      jdbc.getJdbcTemplate().execute("alter table ccd.case_data enable trigger user");
    }
  }

  private void moveSourceCaseOutsideNextDeltaWindow(long id) {
    jdbc.update(
        "update source.case_data set last_modified = timestamp '2000-01-01 00:00:00' where id = :id",
        Map.of("id", id)
    );
  }

  private String targetCaseState(long id) {
    return jdbc.queryForObject("select state from ccd.case_data where id = :id", Map.of("id", id), String.class);
  }

  private String targetCaseData(long id) {
    return jdbc.queryForObject("select data::text from ccd.case_data where id = :id", Map.of("id", id), String.class);
  }

  private String targetCaseEventCaseType(long id) {
    return jdbc.queryForObject(
        "select case_type_id from ccd.case_event where id = :id",
        Map.of("id", id),
        String.class
    );
  }

  private String targetCaseEventIdempotencyKey(long id) {
    return jdbc.queryForObject(
        "select idempotency_key::text from ccd.case_event where id = :id",
        Map.of("id", id),
        String.class
    );
  }

  private boolean targetPrepared() {
    return Boolean.TRUE.equals(jdbc.queryForObject(
        """
        select target_prepared
        from ccd.ccd_data_migration_progress
        where task_name = :taskName
        """,
        Map.of("taskName", "ccd-data-migration"),
        Boolean.class
    ));
  }

  private long targetPreparedProgressRows() {
    return jdbc.queryForObject(
        """
        select count(*)
        from ccd.ccd_data_migration_progress
        where task_name = :taskName
          and target_prepared
        """,
        Map.of("taskName", "ccd-data-migration"),
        Long.class
    );
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

  private void sleepPastProgressWindow() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(ex);
    }
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

  private static class TestMigrationTask extends CcdDataMigrationTask {
    TestMigrationTask(
        NamedParameterJdbcTemplate db,
        PlatformTransactionManager transactionManager,
        CcdDataMigrationTaskOptions options
    ) {
      super(db, transactionManager, options);
    }

    TestMigrationTask(
        NamedParameterJdbcTemplate db,
        PlatformTransactionManager transactionManager,
        CcdDataMigrationTaskOptions options,
        BooleanSupplier decentralisedRuntimeEnabled
    ) {
      super(db, transactionManager, options, decentralisedRuntimeEnabled);
    }
  }

  @Configuration
  @Import(DecentralisedDataConfiguration.class)
  @ImportAutoConfiguration({
      DataSourceAutoConfiguration.class,
      JdbcTemplateAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class,
      TransactionAutoConfiguration.class,
      FlywayAutoConfiguration.class
  })
  static class TestConfig {
  }
}

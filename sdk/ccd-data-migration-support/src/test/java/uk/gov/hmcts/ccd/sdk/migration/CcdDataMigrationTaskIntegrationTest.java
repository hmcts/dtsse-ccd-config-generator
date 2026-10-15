package uk.gov.hmcts.ccd.sdk.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.ccd.sdk.migration.CcdDataMigrationMode.CUTOVER;
import static uk.gov.hmcts.ccd.sdk.migration.CcdDataMigrationMode.PRELOAD_EVENTS;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;

@SpringBootTest(classes = CcdDataMigrationTaskIntegrationTest.TestConfig.class, properties = {
    "spring.datasource.url=jdbc:tc:postgresql:15-alpine:///ccd",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
})
class CcdDataMigrationTaskIntegrationTest {
  private static final long CASE_REVISION_OFFSET = 1_000_000_000L;
  private static final String PERF_CASE_TYPE = "PerfCase";
  private static final String FDW_READER_ROLE = "DTS JIT Access et DB Reader SC\"; select 1; --";

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
    jdbc.getJdbcTemplate().execute("delete from ccd_data_migration.ccd_data_migration_progress");
    jdbc.getJdbcTemplate().execute("delete from ccd_data_migration.case_data_source_xmin");
    jdbc.getJdbcTemplate().execute("truncate table ccd.case_event, ccd.case_data restart identity cascade");
    restoreTargetSchemaState();
    createSourceTables();
    createFdwTables();
  }

  @Test
  void preloadsEventsBySourceHighWaterMarkWithoutDroppingTargetProtections() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertSourceCaseEvent(102, 10, "update", "Updated", "{\"field\":\"two\"}", minutesAgo(5));

    CcdDataMigrationRunResult result = task(PRELOAD_EVENTS, 1000, 10).runMigration();

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
  void preloadTransactionMarginAllowsLowerIdEventToBecomeVisibleBeforeWatermarkAdvances() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(10));
    insertSourceCaseEvent(103, 10, "close", "Closed", "{\"field\":\"three\"}", minutesAgo(1));

    CcdDataMigrationRunResult firstPreload = task(PRELOAD_EVENTS, 1000, 10).runMigration();

    assertThat(firstPreload.eventsProcessed()).isEqualTo(1);
    assertThat(sourceEventHwm()).isEqualTo(101);
    assertThat(countRows("ccd.case_event")).isEqualTo(1);

    insertSourceCaseEvent(102, 10, "update", "Updated", "{\"field\":\"two\"}", minutesAgo(5));

    CcdDataMigrationRunResult secondPreload = task(PRELOAD_EVENTS, 1000, 10).runMigration();

    assertThat(secondPreload.eventsProcessed()).isEqualTo(1);
    assertThat(sourceEventHwm()).isEqualTo(102);
    assertThat(countRows("ccd.case_event")).isEqualTo(2);

    CcdDataMigrationRunResult cutover = task(CUTOVER, 1000, 10).runMigration();

    assertThat(cutover.caughtUp()).isTrue();
    assertThat(cutoverEventHwm()).isEqualTo(103);
    assertThat(countRows("ccd.case_event")).isEqualTo(3);
  }

  @Test
  void cutoverCopiesRemainingEventsRefreshesCaseDataAndMarksComplete() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    task(PRELOAD_EVENTS, 1000, 10).runMigration();

    updateSourceCase(10, 2, "Updated", "{\"field\":\"cutover\"}");
    insertSourceCaseEvent(102, 10, "update", "Updated", "{\"field\":\"cutover\"}", LocalDateTime.now());

    CcdDataMigrationRunResult result = task(CUTOVER, 1000, 10).runMigration();

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
    assertThat(plannerRowEstimate("case_data")).isEqualTo(1);
    assertThat(plannerRowEstimate("case_event")).isEqualTo(2);
  }

  @Test
  void cutoverInsertsSourceCaseDataWithoutPreloadedEvents() {
    insertSourceCase(10, 1000000000000010L, 2, "Updated", "{\"field\":\"source\"}");

    CcdDataMigrationRunResult result = task(CUTOVER, 1000, 10).runMigration();

    assertThat(result.caughtUp()).isTrue();
    assertThat(result.casesProcessed()).isEqualTo(1);
    assertThat(progressStatus()).isEqualTo("COMPLETE");
    assertThat(countRows("ccd.case_data")).isEqualTo(1);
    assertThat(targetCaseState(10)).isEqualTo("Updated");
    assertThat(targetCaseData(10)).isEqualTo("{\"field\": \"source\"}");
    assertThat(caseRevision(10)).isZero();
  }

  @Test
  void cutoverDeletesCasesRemovedFromSourceWithoutDeletingCasesOutsideTheMigrationScope() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    task(PRELOAD_EVENTS, 1000, 10).runMigration();

    insertTargetCase(20, 1000000000000020L, 1, "Submitted", "{\"field\":\"other-type\"}", "OtherCase", 1);
    insertTargetCase(
        30,
        1000000000000030L,
        1,
        "Submitted",
        "{\"field\":\"other-jurisdiction\"}",
        "OTHER",
        "TestCase",
        1
    );
    jdbc.getJdbcTemplate().execute("delete from source.case_event where case_data_id = 10");
    jdbc.getJdbcTemplate().execute("delete from source.case_data where id = 10");

    CcdDataMigrationRunResult result = task(CUTOVER, 1000, 10).runMigration();

    assertThat(result.caughtUp()).isTrue();
    assertThat(countRows("ccd.case_data")).isEqualTo(2);
    assertThat(countRows("ccd.case_event")).isZero();
    assertThat(targetCaseState(20)).isEqualTo("Submitted");
    assertThat(targetCaseState(30)).isEqualTo("Submitted");
  }

  @Test
  void preloadSyncsChangedCaseDataSoCutoverDoesNotRecopyIt() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCase(20, 1000000000000020L, 1, "Submitted", "{\"field\":\"other\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertSourceCaseEvent(102, 20, "create", "Submitted", "{\"field\":\"other\"}", minutesAgo(60));
    task(PRELOAD_EVENTS, 1000, 10).runMigration();

    updateSourceCase(10, 2, "Updated", "{\"field\":\"two\"}");
    CcdDataMigrationRunResult preload = task(PRELOAD_EVENTS, 1000, 10).runMigration();

    assertThat(preload.caughtUp()).isTrue();
    assertThat(preload.casesProcessed()).isEqualTo(1);
    assertThat(targetCaseState(10)).isEqualTo("Updated");
    assertThat(targetCaseData(10)).isEqualTo("{\"field\": \"two\"}");
    assertThat(caseRevision(10)).isZero();
    assertThat(caseDataTriggerEnabled("trigger_increment_case_revision")).isTrue();

    CcdDataMigrationRunResult cutover = task(CUTOVER, 1000, 10).runMigration();

    assertThat(cutover.caughtUp()).isTrue();
    assertThat(cutover.casesProcessed()).isZero();
    assertThat(progressStatus()).isEqualTo("COMPLETE");
    assertThat(caseRevision(10)).isEqualTo(CASE_REVISION_OFFSET + 1);
    assertThat(caseRevision(20)).isEqualTo(CASE_REVISION_OFFSET + 1);
  }

  @Test
  void preloadSyncInsertsSourceCasesThatHaveNoEventsYet() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");

    CcdDataMigrationRunResult preload = task(PRELOAD_EVENTS, 1000, 10).runMigration();

    assertThat(preload.casesProcessed()).isEqualTo(1);
    assertThat(targetCaseData(10)).isEqualTo("{\"field\": \"one\"}");
    assertThat(caseRevision(10)).isZero();
  }

  @Test
  void cutoverSyncsSupplementaryDataChangedWithoutVersionOrLastModified() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    task(PRELOAD_EVENTS, 1000, 10).runMigration();

    // CCD's supplementary data endpoints update the column without touching version or last_modified.
    jdbc.getJdbcTemplate().execute("""
        update source.case_data
        set supplementary_data = '{"orgs_assigned_users": {"ORG1": 2}}'::jsonb
        where id = 10
        """);

    CcdDataMigrationRunResult cutover = task(CUTOVER, 1000, 10).runMigration();

    assertThat(cutover.casesProcessed()).isEqualTo(1);
    assertThat(targetSupplementaryData(10)).isEqualTo("{\"orgs_assigned_users\": {\"ORG1\": 2}}");
    assertThat(caseRevision(10)).isEqualTo(CASE_REVISION_OFFSET + 1);
  }

  @Test
  void cutoverTreatsNullSourceSupplementaryDataAsEmpty() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    jdbc.getJdbcTemplate().execute("update source.case_data set supplementary_data = null where id = 10");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    task(PRELOAD_EVENTS, 1000, 10).runMigration();

    CcdDataMigrationRunResult cutover = task(CUTOVER, 1000, 10).runMigration();

    assertThat(cutover.casesProcessed()).isZero();
    assertThat(targetSupplementaryData(10)).isEqualTo("{}");
  }

  @Test
  void cutoverRefreshesDataWhenEitherVersionOrLastModifiedChanges() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCase(20, 1000000000000020L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertSourceCaseEvent(102, 20, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    task(PRELOAD_EVENTS, 1000, 10).runMigration();

    jdbc.getJdbcTemplate().execute("""
        update source.case_data set data = '{"field":"version-only"}'::jsonb, version = 2 where id = 10
        """);
    jdbc.getJdbcTemplate().execute("""
        update source.case_data
        set data = '{"field":"last-modified-only"}'::jsonb,
            last_modified = timestamp '2024-06-01 00:00:00'
        where id = 20
        """);

    CcdDataMigrationRunResult cutover = task(CUTOVER, 1000, 10).runMigration();

    assertThat(cutover.casesProcessed()).isEqualTo(2);
    assertThat(targetCaseData(10)).isEqualTo("{\"field\": \"version-only\"}");
    assertThat(targetCaseData(20)).isEqualTo("{\"field\": \"last-modified-only\"}");
  }

  @Test
  void cutoverRecopiesDataChangedDirectlyInTheSource() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCase(20, 1000000000000020L, 1, "Submitted", "{\"field\":\"two\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertSourceCaseEvent(102, 20, "create", "Submitted", "{\"field\":\"two\"}", minutesAgo(60));
    task(PRELOAD_EVENTS, 1000, 10).runMigration();

    // A direct SQL fix in the source changes data without touching version or last_modified.
    jdbc.getJdbcTemplate().execute("""
        update source.case_data set data = '{"field":"direct-sql"}'::jsonb where id = 10
        """);

    CcdDataMigrationRunResult cutover = task(CUTOVER, 1000, 10).runMigration();

    assertThat(cutover.casesProcessed()).isEqualTo(1);
    assertThat(progressStatus()).isEqualTo("COMPLETE");
    assertThat(targetCaseData(10)).isEqualTo("{\"field\": \"direct-sql\"}");
    assertThat(targetCaseData(20)).isEqualTo("{\"field\": \"two\"}");
    assertThat(caseRevision(10)).isEqualTo(CASE_REVISION_OFFSET + 1);
  }

  @Test
  void syncRecopiesCaseWhenOnlyAnUnmigratedColumnChanges() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    task(PRELOAD_EVENTS, 1000, 10).runMigration();

    // Logstash marks a case as indexed by updating a column the migration does not copy. The row
    // version still changes, so the case is re-read; the target row is left as it was.
    jdbc.getJdbcTemplate().execute("update source.case_data set marked_by_logstash = true where id = 10");

    CcdDataMigrationRunResult preload = task(PRELOAD_EVENTS, 1000, 10).runMigration();
    CcdDataMigrationRunResult cutover = task(CUTOVER, 1000, 10).runMigration();

    assertThat(preload.casesProcessed()).isZero();
    assertThat(sourceXminRecorded(10)).isEqualTo(sourceXmin(10));
    assertThat(cutover.casesProcessed()).isZero();
    assertThat(targetCaseData(10)).isEqualTo("{\"field\": \"one\"}");
  }

  @Test
  void syncRecopiesCasesPreloadedBeforeSourceVersionsWereRecorded() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    task(preloadWithoutCaseDataSync(1000, 10)).runMigration();
    jdbc.getJdbcTemplate().execute("update source.case_data set data = '{\"field\":\"later\"}'::jsonb where id = 10");

    assertThat(sourceXminRecorded(10)).isNull();

    CcdDataMigrationRunResult cutover = task(CUTOVER, 1000, 10).runMigration();

    assertThat(cutover.casesProcessed()).isEqualTo(1);
    assertThat(targetCaseData(10)).isEqualTo("{\"field\": \"later\"}");
    assertThat(sourceXminRecorded(10)).isEqualTo(sourceXmin(10));
  }

  @Test
  void cutoverRemovesRecordedSourceVersionsOfDeletedCases() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    task(PRELOAD_EVENTS, 1000, 10).runMigration();
    assertThat(sourceXminRecorded(10)).isNotNull();

    jdbc.getJdbcTemplate().execute("delete from source.case_event where case_data_id = 10");
    jdbc.getJdbcTemplate().execute("delete from source.case_data where id = 10");
    task(CUTOVER, 1000, 10).runMigration();

    assertThat(countRows("ccd.case_data")).isZero();
    assertThat(sourceXminRecorded(10)).isNull();
  }

  @Test
  void preloadCaseDataSyncStopsAtTimeLimitAndResumesOnNextRun() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCase(20, 1000000000000020L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertSourceCaseEvent(102, 20, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    task(PRELOAD_EVENTS, 1000, 10).runMigration();

    updateSourceCase(10, 2, "Updated", "{\"field\":\"two\"}");
    updateSourceCase(20, 2, "Updated", "{\"field\":\"two\"}");
    var timeLimited = optionsBuilder(List.of("TestCase"))
        .mode(PRELOAD_EVENTS)
        .caseDataSyncBatchSize(1)
        .maxRunTime(Duration.ofMinutes(1))
        .build();

    CcdDataMigrationRunResult first = new CcdDataMigrationTask(
        jdbc,
        new TransactionTemplate(transactionManager),
        timeLimited,
        new HourlyTickingClock(),
        () -> false
    ).runMigration();

    assertThat(first.caughtUp()).isFalse();
    assertThat(first.stoppedByTimeLimit()).isTrue();
    assertThat(first.casesProcessed()).isEqualTo(1);
    assertThat(targetCaseData(10)).isEqualTo("{\"field\": \"two\"}");
    assertThat(targetCaseData(20)).isEqualTo("{\"field\": \"one\"}");

    CcdDataMigrationRunResult second = task(PRELOAD_EVENTS, 1000, 10).runMigration();

    assertThat(second.caughtUp()).isTrue();
    assertThat(second.casesProcessed()).isEqualTo(1);
    assertThat(targetCaseData(20)).isEqualTo("{\"field\": \"two\"}");
  }

  @Test
  void preloadSkipsCaseDataSyncWhenDisabled() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    task(preloadWithoutCaseDataSync(1000, 10)).runMigration();

    updateSourceCase(10, 2, "Updated", "{\"field\":\"two\"}");
    CcdDataMigrationRunResult preload = task(preloadWithoutCaseDataSync(1000, 10)).runMigration();

    assertThat(preload.casesProcessed()).isZero();
    assertThat(targetCaseData(10)).isEqualTo("{\"field\": \"one\"}");
  }

  @Test
  void copiesSignificantItemsInSingleCutoverQuery() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertSourceCaseEvent(102, 10, "update", "Updated", "{\"field\":\"two\"}", minutesAgo(30));
    insertSourceSignificantItem(5001, 101, "First document", "http://dm-store/documents/first");
    insertSourceSignificantItem(5002, 102, "Second document", "http://dm-store/documents/second");

    CcdDataMigrationRunResult preloadResult = task(PRELOAD_EVENTS, 101, 1).runMigration();

    assertThat(preloadResult.caughtUp()).isFalse();
    assertThat(countRows("ccd.case_event_significant_items")).isZero();
    assertThat(sourceEventHwm()).isEqualTo(101);
    insertTargetSignificantItem(5001, 101, "Existing document", "http://dm-store/documents/existing");

    CcdDataMigrationRunResult cutoverResult = task(CUTOVER, 1000, 10).runMigration();

    assertThat(cutoverResult.caughtUp()).isTrue();
    assertThat(progressStatus()).isEqualTo("COMPLETE");
    assertThat(countRows("ccd.case_event_significant_items")).isEqualTo(2);
    assertThat(significantItemDescription(5001)).isEqualTo("Existing document");
    assertThat(significantItemDescription(5002)).isEqualTo("Second document");
    assertThat(caseEventSignificantItemsSequenceLastValue()).isGreaterThanOrEqualTo(5002);
  }

  @Test
  void togglesPreloadAndCutoverWithSignificantItemDelta() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(100, 10, "prepare", "Submitted", "{\"field\":\"one\"}", minutesAgo(90));
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertSourceSignificantItem(5001, 101, "First document", "http://dm-store/documents/first");

    CcdDataMigrationRunResult firstCutover = task(CUTOVER, 1000, 10).runMigration();

    assertThat(firstCutover.caughtUp()).isTrue();
    assertThat(progressStatus()).isEqualTo("COMPLETE");
    assertThat(cutoverEventHwm()).isEqualTo(101);
    assertThat(sourceEventHwm()).isEqualTo(101);
    assertThat(significantItemsHwm()).isEqualTo(5001);
    assertThat(countRows("ccd.case_event_significant_items")).isEqualTo(1);

    updateSourceCase(10, 2, "Updated", "{\"field\":\"two\"}");
    insertSourceCaseEvent(102, 10, "update", "Updated", "{\"field\":\"two\"}", minutesAgo(30));
    insertSourceSignificantItem(5002, 102, "Second document", "http://dm-store/documents/second");
    insertSourceSignificantItem(5003, 100, "Late old event document", "http://dm-store/documents/late-old-event");

    CcdDataMigrationRunResult preload = task(PRELOAD_EVENTS, 1000, 10).runMigration();

    assertThat(preload.caughtUp()).isTrue();
    assertThat(progressStatus()).isEqualTo("PRELOAD");
    assertThat(sourceEventHwm()).isEqualTo(102);
    assertThat(significantItemsHwm()).isEqualTo(5001);
    assertThat(countRows("ccd.case_event_significant_items")).isEqualTo(1);

    CcdDataMigrationRunResult secondCutover = task(CUTOVER, 1000, 10).runMigration();

    assertThat(secondCutover.caughtUp()).isTrue();
    assertThat(progressStatus()).isEqualTo("COMPLETE");
    assertThat(cutoverEventHwm()).isEqualTo(102);
    assertThat(sourceEventHwm()).isEqualTo(102);
    assertThat(significantItemsHwm()).isEqualTo(5003);
    assertThat(countRows("ccd.case_event")).isEqualTo(3);
    assertThat(countRows("ccd.case_event_significant_items")).isEqualTo(3);
    assertThat(significantItemDescription(5001)).isEqualTo("First document");
    assertThat(significantItemDescription(5002)).isEqualTo("Second document");
    assertThat(significantItemDescription(5003)).isEqualTo("Late old event document");

    updateSourceCase(10, 3, "Closed", "{\"field\":\"three\"}");
    insertSourceCaseEvent(103, 10, "close", "Closed", "{\"field\":\"three\"}", LocalDateTime.now());

    task(PRELOAD_EVENTS, 1000, 10).runMigration();
    CcdDataMigrationRunResult thirdCutover = task(CUTOVER, 1000, 10).runMigration();

    assertThat(thirdCutover.caughtUp()).isTrue();
    assertThat(cutoverEventHwm()).isEqualTo(103);
    assertThat(sourceEventHwm()).isEqualTo(103);
    assertThat(significantItemsHwm()).isEqualTo(5003);
    assertThat(countRows("ccd.case_event")).isEqualTo(4);
    assertThat(countRows("ccd.case_event_significant_items")).isEqualTo(3);
  }

  @Test
  void batchesSignificantItemsAcrossCutoverRuns() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertSourceCaseEvent(102, 10, "update", "Updated", "{\"field\":\"two\"}", minutesAgo(30));
    insertSourceSignificantItem(5001, 101, "First document", "http://dm-store/documents/first");
    insertSourceSignificantItem(10002, 102, "Second document", "http://dm-store/documents/second");

    CcdDataMigrationRunResult firstCutover = task(CUTOVER, 1000, 1, 5001).runMigration();

    assertThat(firstCutover.caughtUp()).isFalse();
    assertThat(significantItemsHwm()).isEqualTo(5001);
    assertThat(countRows("ccd.case_event_significant_items")).isEqualTo(1);

    CcdDataMigrationRunResult secondCutover = task(CUTOVER, 1000, 1, 5001).runMigration();

    assertThat(secondCutover.caughtUp()).isTrue();
    assertThat(significantItemsHwm()).isEqualTo(10002);
    assertThat(countRows("ccd.case_event_significant_items")).isEqualTo(2);
  }

  @Test
  void completedCutoverRerunCopiesSignificantItemsBeforeValidation() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertSourceSignificantItem(5001, 101, "First document", "http://dm-store/documents/first");
    insertTargetCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}", CASE_REVISION_OFFSET + 1);
    insertTargetEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", 1);
    createCompleteProgress(101);
    jdbc.getJdbcTemplate().execute("delete from ccd.es_queue");

    CcdDataMigrationRunResult result = task(CUTOVER, 1000, 10).runMigration();

    assertThat(result.caughtUp()).isTrue();
    assertThat(result.eventsProcessed()).isZero();
    assertThat(sourceEventHwm()).isEqualTo(101);
    assertThat(countRows("ccd.case_event")).isEqualTo(1);
    assertThat(countRows("ccd.case_event_significant_items")).isEqualTo(1);
    assertThat(significantItemDescription(5001)).isEqualTo("First document");
  }

  @Test
  void createsMissingFdwTablesFromExistingFdwConfiguration() {
    jdbc.getJdbcTemplate().execute("drop foreign table fdw_stage.case_event");
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertSourceSignificantItem(5001, 101, "First document", "http://dm-store/documents/first");

    CcdDataMigrationRunResult result = task(PRELOAD_EVENTS, 1000, 10).runMigration();

    assertThat(result.caughtUp()).isTrue();
    assertThat(countRows("ccd.case_event")).isEqualTo(1);
    assertThat(countRows("ccd.case_event_significant_items")).isZero();
    assertThat(fdwTableExists("case_data")).isTrue();
    assertThat(fdwTableExists("case_event")).isTrue();
    assertThat(fdwTableExists("case_event_significant_items")).isTrue();
    assertThat(fdwTableExists("case_data_xmin")).isTrue();
  }

  @Test
  void enablesRemoteEstimateOnFdwServerCreatedWithoutIt() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    assertThat(fdwServerOption("use_remote_estimate")).isNull();

    CcdDataMigrationRunResult result = task(PRELOAD_EVENTS, 1000, 10).runMigration();

    assertThat(result.caughtUp()).isTrue();
    assertThat(countRows("ccd.case_event")).isEqualTo(1);
    assertThat(fdwServerOption("use_remote_estimate")).isEqualTo("true");
  }

  @Test
  void leavesExplicitRemoteEstimateSettingUnchanged() {
    jdbc.getJdbcTemplate().execute(
        "alter server ccd_migration_test_server options (add use_remote_estimate 'false')"
    );
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));

    CcdDataMigrationRunResult result = task(PRELOAD_EVENTS, 1000, 10).runMigration();

    assertThat(result.caughtUp()).isTrue();
    assertThat(fdwServerOption("use_remote_estimate")).isEqualTo("false");
  }

  @Test
  void grantsFdwSelectToConfiguredAdditionalGrantee() {
    createRole(FDW_READER_ROLE);
    createFdwUserMapping(FDW_READER_ROLE);
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertSourceSignificantItem(5001, 101, "First document", "http://dm-store/documents/first");

    var options = optionsBuilder(List.of("TestCase"))
        .mode(PRELOAD_EVENTS)
        .fdwAdditionalSelectGrantee(FDW_READER_ROLE)
        .build();

    CcdDataMigrationRunResult result = new CcdDataMigrationTask(jdbc, transactionManager, options).runMigration();

    assertThat(result.caughtUp()).isTrue();
    assertThat(countFdwRowsAsRole(FDW_READER_ROLE, "case_data")).isEqualTo(1);
    assertThat(countFdwRowsAsRole(FDW_READER_ROLE, "case_event")).isEqualTo(1);
    assertThat(countFdwRowsAsRole(FDW_READER_ROLE, "case_event_significant_items")).isEqualTo(1);
  }

  @Test
  void cutoverDeletesOnlyMigratedCaseTypeElasticsearchQueueRows() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertTargetCase(20, 1000000000000020L, 1, "Submitted", "{\"field\":\"other\"}", "OtherCase", 1);

    CcdDataMigrationRunResult result = task(CUTOVER, 1000, 10).runMigration();

    assertThat(result.caughtUp()).isTrue();
    assertThat(progressStatus()).isEqualTo("COMPLETE");
    assertElasticsearchQueueEmpty("TestCase");
    assertThat(countElasticsearchQueueRows("OtherCase")).isEqualTo(1);
    assertThat(caseDataTriggerEnabled("trigger_enqueue_case_revision")).isTrue();
  }

  @Test
  void cutoverScopesCaseDataRefreshAndQueueCleanupBySourceJurisdiction() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    task(PRELOAD_EVENTS, 1000, 10).runMigration();

    insertSourceCase(20, 1000000000000020L, 2, "Updated", "{\"field\":\"source-other\"}", "OTHER", "TestCase");
    insertTargetCase(20, 1000000000000020L, 1, "Submitted", "{\"field\":\"target-other\"}", "OTHER", "TestCase", 5);
    insertElasticsearchQueueRow(1000000000000020L, 5);

    CcdDataMigrationRunResult result = task(CUTOVER, 1000, 10).runMigration();

    assertThat(result.caughtUp()).isTrue();
    assertThat(progressStatus()).isEqualTo("COMPLETE");
    assertThat(targetCaseState(20)).isEqualTo("Submitted");
    assertThat(targetCaseData(20)).isEqualTo("{\"field\": \"target-other\"}");
    assertThat(caseRevision(20)).isEqualTo(5);
    assertThat(countElasticsearchQueueRows("TestCase")).isEqualTo(1);
    assertThat(caseDataTriggerEnabled("trigger_enqueue_case_revision")).isTrue();
  }

  @Test
  void completedCutoverRerunDeletesMigratedQueueRowsAndEnablesElasticsearchQueueTrigger() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertTargetCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}", 1);
    createCompleteProgress(101);
    jdbc.getJdbcTemplate().execute("alter table ccd.case_data disable trigger trigger_enqueue_case_revision");

    CcdDataMigrationRunResult result = task(CUTOVER, 101, 10).runMigration();

    assertThat(result.caughtUp()).isTrue();
    assertThat(cutoverEventHwm()).isEqualTo(101);
    assertThat(countElasticsearchQueueRows("TestCase")).isZero();
    assertThat(caseDataTriggerEnabled("trigger_enqueue_case_revision")).isTrue();
  }

  @Test
  void preloadFailsWhenTargetEventsExistWithoutSourceProgress() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertSourceCaseEvent(102, 10, "update", "Updated", "{\"field\":\"two\"}", minutesAgo(60));
    insertTargetCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}", 0);
    insertTargetEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", 1);
    createProgress();

    assertThatThrownBy(() -> task(PRELOAD_EVENTS, 10, 10).runMigration())
        .isInstanceOf(CcdDataMigrationException.class)
        .hasMessageContaining("target already contains migrated events")
        .hasMessageContaining("source_event_hwm is zero");
    assertThat(countRows("ccd.case_event")).isEqualTo(1);
    assertThat(localEventHwm()).isEqualTo(101);
    assertThat(sourceEventHwm()).isZero();
  }

  @Test
  void preloadBatchesByFixedSourceEventIdWindow() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertSourceCaseEvent(1001, 10, "update", "Updated", "{\"field\":\"two\"}", minutesAgo(60));
    insertSourceCaseEvent(2001, 10, "close", "Closed", "{\"field\":\"three\"}", minutesAgo(60));
    insertSourceCaseEvent(102, 10, "other", "Submitted", "{\"field\":\"other\"}", "OtherCase", minutesAgo(60));

    CcdDataMigrationRunResult result = task(PRELOAD_EVENTS, 1000, 1).runMigration();

    assertThat(result.caughtUp()).isFalse();
    assertThat(result.eventsProcessed()).isEqualTo(1);
    assertThat(countRows("ccd.case_event")).isEqualTo(1);
    assertThat(localEventHwm()).isEqualTo(101);
    assertThat(sourceEventHwm()).isEqualTo(1000);
  }

  @Test
  void preloadInsertsEachProvisionalCaseOnceAcrossWindows() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCase(20, 1000000000000020L, 1, "Submitted", "{\"field\":\"other\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertSourceCaseEvent(102, 10, "update", "Updated", "{\"field\":\"two\"}", minutesAgo(60));
    insertSourceCaseEvent(103, 20, "create", "Submitted", "{\"field\":\"other\"}", minutesAgo(60));

    task(preloadWithoutCaseDataSync(100, 2)).runMigration();
    assertThat(countRows("ccd.case_data")).isEqualTo(2);
    assertThat(targetCaseData(10)).isEqualTo("{\"field\": \"one\"}");

    updateSourceCase(10, 2, "Closed", "{\"field\":\"three\"}");
    insertSourceCaseEvent(201, 10, "close", "Closed", "{\"field\":\"three\"}", minutesAgo(60));
    CcdDataMigrationRunResult result = task(preloadWithoutCaseDataSync(100, 10)).runMigration();

    assertThat(result.caughtUp()).isTrue();
    assertThat(countRows("ccd.case_event")).isEqualTo(4);
    assertThat(countRows("ccd.case_data")).isEqualTo(2);
    assertThat(targetCaseData(10)).isEqualTo("{\"field\": \"one\"}");
    assertThat(caseEventRevision(101)).isEqualTo(1);
    assertThat(caseEventRevision(102)).isEqualTo(2);
    assertThat(caseEventRevision(201)).isEqualTo(3);
    assertThat(caseEventRevision(103)).isEqualTo(1);
  }

  @Test
  void preloadIgnoresHigherIdEventsFromOtherCaseTypes() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertSourceCaseEvent(900000, 10, "other", "Submitted", "{\"field\":\"other\"}", "OtherCase", minutesAgo(60));

    CcdDataMigrationRunResult firstRun = task(PRELOAD_EVENTS, 1000, 10).runMigration();

    assertThat(firstRun.caughtUp()).isTrue();
    assertThat(sourceEventHwm()).isEqualTo(101);

    insertSourceCaseEvent(102, 10, "update", "Updated", "{\"field\":\"two\"}", minutesAgo(30));
    CcdDataMigrationRunResult secondRun = task(PRELOAD_EVENTS, 1000, 10).runMigration();

    assertThat(secondRun.eventsProcessed()).isEqualTo(1);
    assertThat(countRows("ccd.case_event")).isEqualTo(2);
    assertThat(sourceEventHwm()).isEqualTo(102);
  }

  @Test
  void preloadRewindsProgressThatOvershotMigratedCaseTypeEvents() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    task(PRELOAD_EVENTS, 1000, 10).runMigration();
    jdbc.update(
        "update ccd_data_migration.ccd_data_migration_progress set source_event_hwm = 900000",
        Map.of()
    );

    insertSourceCaseEvent(102, 10, "update", "Updated", "{\"field\":\"two\"}", minutesAgo(30));
    CcdDataMigrationRunResult result = task(PRELOAD_EVENTS, 1000, 10).runMigration();

    assertThat(result.caughtUp()).isTrue();
    assertThat(result.eventsProcessed()).isEqualTo(1);
    assertThat(countRows("ccd.case_event")).isEqualTo(2);
    assertThat(caseEventRevision(102)).isEqualTo(2);
    assertThat(sourceEventHwm()).isEqualTo(102);
  }

  @Test
  void cutoverIgnoresHigherIdEventsFromOtherCaseTypes() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertSourceCaseEvent(900000, 10, "other", "Submitted", "{\"field\":\"other\"}", "OtherCase", minutesAgo(60));
    task(PRELOAD_EVENTS, 1000, 10).runMigration();

    insertSourceCaseEvent(102, 10, "update", "Updated", "{\"field\":\"two\"}", LocalDateTime.now());
    CcdDataMigrationRunResult result = task(CUTOVER, 1000, 10).runMigration();

    assertThat(result.caughtUp()).isTrue();
    assertThat(cutoverEventHwm()).isEqualTo(102);
    assertThat(countRows("ccd.case_event")).isEqualTo(2);
    assertThat(progressStatus()).isEqualTo("COMPLETE");
  }

  @Test
  void preloadAdvancesAcrossEmptySourceEventIdWindows() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertSourceCaseEvent(1001, 10, "update", "Updated", "{\"field\":\"two\"}", minutesAgo(60));

    CcdDataMigrationRunResult firstRun = task(PRELOAD_EVENTS, 100, 1).runMigration();
    CcdDataMigrationRunResult secondRun = task(PRELOAD_EVENTS, 100, 1).runMigration();
    CcdDataMigrationRunResult thirdRun = task(PRELOAD_EVENTS, 100, 1).runMigration();

    assertThat(firstRun.eventsProcessed()).isZero();
    assertThat(secondRun.caughtUp()).isFalse();
    assertThat(secondRun.eventsProcessed()).isEqualTo(1);
    assertThat(thirdRun.eventsProcessed()).isZero();
    assertThat(localEventHwm()).isEqualTo(101);
    assertThat(sourceEventHwm()).isEqualTo(300);
  }

  @Test
  void preloadBatchBoundaryUsesSourceJurisdiction() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCase(20, 1000000000000020L, 1, "Submitted", "{\"field\":\"other\"}", "OTHER", "TestCase");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertSourceCaseEvent(102, 20, "other-create", "Submitted", "{\"field\":\"other\"}", "TestCase", minutesAgo(60));
    insertSourceCaseEvent(1001, 10, "update", "Updated", "{\"field\":\"two\"}", minutesAgo(60));

    CcdDataMigrationRunResult result = task(PRELOAD_EVENTS, 1001, 1).runMigration();

    assertThat(result.eventsProcessed()).isEqualTo(2);
    assertThat(countRows("ccd.case_event")).isEqualTo(2);
    assertThat(localEventHwm()).isEqualTo(1001);
    assertThat(sourceEventHwm()).isEqualTo(1001);
  }

  @Test
  void preloadTreatsUnmatchedSourceJurisdictionAsCaughtUp() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));

    var options = CcdDataMigrationTaskOptions.builder(List.of("TestCase"))
        .sourceJurisdiction("OTHER")
        .mode(PRELOAD_EVENTS)
        .build();

    CcdDataMigrationRunResult result = new CcdDataMigrationTask(jdbc, transactionManager, options).runMigration();

    assertThat(result.caughtUp()).isTrue();
    assertThat(result.eventsProcessed()).isZero();
    assertThat(countRows("ccd.case_event")).isZero();
    assertThat(sourceEventHwm()).isEqualTo(101);
  }

  @Test
  void cutoverPausesBeforeFinalRefreshWhenRuntimeLimitStopsEventCopying() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertSourceCaseEvent(102, 10, "update", "Updated", "{\"field\":\"two\"}", minutesAgo(60));

    CcdDataMigrationRunResult result = task(CUTOVER, 101, 1).runMigration();

    assertThat(result.caughtUp()).isFalse();
    assertThat(progressStatus()).isEqualTo("CUTOVER");
    assertThat(localEventHwm()).isEqualTo(101);
    assertThat(countRows("ccd.case_event")).isEqualTo(1);
    assertThat(caseRevision(10)).isZero();
    assertElasticsearchQueueEmpty();
    assertThat(caseDataTriggerEnabled("trigger_enqueue_case_revision")).isFalse();
  }

  @Test
  void resumesPreloadAfterCutoverIsPaused() {
    insertSourceCase(10, 1000000000000010L, 1, "Submitted", "{\"field\":\"one\"}");
    insertSourceCaseEvent(101, 10, "create", "Submitted", "{\"field\":\"one\"}", minutesAgo(60));
    insertSourceCaseEvent(102, 10, "update", "Updated", "{\"field\":\"two\"}", minutesAgo(30));

    CcdDataMigrationRunResult cutover = task(CUTOVER, 101, 1).runMigration();

    assertThat(cutover.caughtUp()).isFalse();
    assertThat(progressStatus()).isEqualTo("CUTOVER");
    assertThat(cutoverEventHwm()).isEqualTo(102);
    assertThat(sourceEventHwm()).isEqualTo(101);

    insertSourceCaseEvent(103, 10, "update", "Updated", "{\"field\":\"three\"}", minutesAgo(5));

    CcdDataMigrationRunResult preload = task(PRELOAD_EVENTS, 1000, 10).runMigration();

    assertThat(preload.caughtUp()).isTrue();
    assertThat(preload.eventsProcessed()).isEqualTo(2);
    assertThat(progressStatus()).isEqualTo("PRELOAD");
    assertThat(cutoverEventHwm()).isNull();
    assertThat(sourceEventHwm()).isEqualTo(103);
    assertThat(countRows("ccd.case_event")).isEqualTo(3);
    assertThat(caseDataTriggerEnabled("trigger_enqueue_case_revision")).isFalse();
    assertTargetProtectionsPresent();
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
    var options = optionsBuilder(List.of("TestCase")).build();

    assertThatThrownBy(() -> new CcdDataMigrationTask(jdbc, transactionManager, options, () -> true).runMigration())
        .isInstanceOf(CcdDataMigrationException.class)
        .hasMessageContaining("cannot run while the decentralised runtime is enabled")
        .hasMessageContaining("live case writes for the migrated case types still go to source CCD");

    assertThat(countRows("ccd.case_data")).isZero();
    assertTargetProtectionsPresent();
  }

  @Test
  void migratesSeededDatasetWithinPerfHarnessLimit() {
    int caseCount = Integer.getInteger("ccd.data-migration.perf.cases", 10_000);
    int eventsPerCase = Integer.getInteger("ccd.data-migration.perf.events-per-case", 10);
    int eventIdWindowSize = Integer.getInteger("ccd.data-migration.perf.event-id-window-size", 10_000);
    final Duration maxElapsed = Duration.ofSeconds(Long.getLong("ccd.data-migration.perf.max-seconds", 900L));

    seedSourceDataset(PERF_CASE_TYPE, caseCount, eventsPerCase);

    Instant started = Instant.now();
    CcdDataMigrationRunResult preloadResult = new CcdDataMigrationTask(
        jdbc,
        transactionManager,
        CcdDataMigrationTaskOptions.builder(List.of(PERF_CASE_TYPE))
            .sourceJurisdiction("TEST")
            .mode(PRELOAD_EVENTS)
            .eventIdWindowSize(eventIdWindowSize)
            .build()
    ).runMigration();
    CcdDataMigrationRunResult cutoverResult = new CcdDataMigrationTask(
        jdbc,
        transactionManager,
        CcdDataMigrationTaskOptions.builder(List.of(PERF_CASE_TYPE))
            .sourceJurisdiction("TEST")
            .mode(CUTOVER)
            .eventIdWindowSize(eventIdWindowSize)
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
      int eventIdWindowSize,
      int maxBatchesPerRun
  ) {
    return task(mode, eventIdWindowSize, maxBatchesPerRun, 100_000);
  }

  private CcdDataMigrationTask task(
      CcdDataMigrationMode mode,
      int eventIdWindowSize,
      int maxBatchesPerRun,
      int significantItemIdWindowSize
  ) {
    var options = optionsBuilder(List.of("TestCase"))
        .mode(mode)
        .eventIdWindowSize(eventIdWindowSize)
        .significantItemIdWindowSize(significantItemIdWindowSize)
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
          id bigint not null unique,
          marked_by_logstash boolean default false
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
    jdbc.getJdbcTemplate().execute("""
        create table source.case_event_significant_items (
          id bigint primary key,
          description varchar(64) not null,
          "type" text not null,
          url text,
          case_event_id bigint not null
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
        options (host '127.0.0.1', port '5432', dbname 'ccd')
        """);
    jdbc.getJdbcTemplate().execute("""
        create user mapping for current_user
        server ccd_migration_test_server
        options (user 'test', password 'test', password_required 'false')
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

  private void insertSourceSignificantItem(long id, long caseEventId, String description, String url) {
    jdbc.update(
        """
        insert into source.case_event_significant_items (
          id,
          description,
          "type",
          url,
          case_event_id
        ) values (
          :id,
          :description,
          'DOCUMENT',
          :url,
          :caseEventId
        )
        """,
        new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("description", description)
            .addValue("url", url)
            .addValue("caseEventId", caseEventId)
    );
  }

  private void insertTargetSignificantItem(long id, long caseEventId, String description, String url) {
    jdbc.update(
        """
        insert into ccd.case_event_significant_items (
          id,
          description,
          "type",
          url,
          case_event_id
        ) values (
          :id,
          :description,
          'DOCUMENT',
          :url,
          :caseEventId
        )
        """,
        new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("description", description)
            .addValue("url", url)
            .addValue("caseEventId", caseEventId)
    );
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
    insertSourceCase(id, reference, version, state, data, "TEST", "TestCase");
  }

  private void insertSourceCase(
      long id,
      long reference,
      int version,
      String state,
      String data,
      String jurisdiction,
      String caseTypeId
  ) {
    var params = new MapSqlParameterSource()
        .addValue("id", id)
        .addValue("reference", reference)
        .addValue("version", version)
        .addValue("state", state)
        .addValue("data", data)
        .addValue("jurisdiction", jurisdiction)
        .addValue("caseTypeId", caseTypeId);
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
          :jurisdiction,
          :caseTypeId,
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
    insertTargetCase(id, reference, version, state, data, "TEST", "TestCase", caseRevision);
  }

  private void insertTargetCase(
      long id,
      long reference,
      int version,
      String state,
      String data,
      String caseTypeId,
      long caseRevision
  ) {
    insertTargetCase(id, reference, version, state, data, "TEST", caseTypeId, caseRevision);
  }

  private void insertTargetCase(
      long id,
      long reference,
      int version,
      String state,
      String data,
      String jurisdiction,
      String caseTypeId,
      long caseRevision
  ) {
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
          :jurisdiction,
          :caseTypeId,
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
            .addValue("jurisdiction", jurisdiction)
            .addValue("caseTypeId", caseTypeId)
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
        insert into ccd_data_migration.ccd_data_migration_progress (
          task_name,
          config_hash
        ) values (
          :taskName,
          :configHash
        )
        """,
        new MapSqlParameterSource()
            .addValue("taskName", "ccd-data-migration")
            .addValue("configHash", optionsBuilder(List.of("TestCase"))
                .build()
                .migrationConfigHash())
    );
  }

  private void createCompleteProgress(long cutoverEventHwm) {
    jdbc.update(
        """
        insert into ccd_data_migration.ccd_data_migration_progress (
          task_name,
          config_hash,
          status,
          cutover_event_hwm
        ) values (
          :taskName,
          :configHash,
          'COMPLETE',
          :cutoverEventHwm
        )
        """,
        new MapSqlParameterSource()
            .addValue("taskName", "ccd-data-migration")
            .addValue("configHash", optionsBuilder(List.of("TestCase"))
                .build()
                .migrationConfigHash())
            .addValue("cutoverEventHwm", cutoverEventHwm)
    );
  }

  private CcdDataMigrationTaskOptions preloadWithoutCaseDataSync(int eventIdWindowSize, int maxBatchesPerRun) {
    return optionsBuilder(List.of("TestCase"))
        .mode(PRELOAD_EVENTS)
        .eventIdWindowSize(eventIdWindowSize)
        .maxBatchesPerRun(maxBatchesPerRun)
        .preloadCaseDataSync(false)
        .build();
  }

  private CcdDataMigrationTask task(CcdDataMigrationTaskOptions options) {
    return new CcdDataMigrationTask(jdbc, transactionManager, options);
  }

  private CcdDataMigrationTaskOptions.Builder optionsBuilder(List<String> caseTypeIds) {
    return CcdDataMigrationTaskOptions.builder(caseTypeIds)
        .sourceJurisdiction("TEST");
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
          ((case_number - 1) * :eventsPerCase) + event_number,
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

  private long countElasticsearchQueueRows(String caseTypeId) {
    return jdbc.queryForObject(
        """
        select count(*)
        from ccd.es_queue queue
        join ccd.case_data case_data on case_data.reference = queue.reference
        where case_data.case_type_id = :caseTypeId
        """,
        Map.of("caseTypeId", caseTypeId),
        Long.class
    );
  }

  private void insertElasticsearchQueueRow(long reference, long caseRevision) {
    jdbc.update(
        """
        insert into ccd.es_queue(reference, case_revision)
        values (:reference, :caseRevision)
        """,
        Map.of("reference", reference, "caseRevision", caseRevision)
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

  private String targetSupplementaryData(long id) {
    return jdbc.queryForObject(
        "select supplementary_data::text from ccd.case_data where id = :id",
        Map.of("id", id),
        String.class
    );
  }

  private Long sourceXminRecorded(long id) {
    return jdbc.query(
        "select source_xmin from ccd_data_migration.case_data_source_xmin where case_data_id = :id",
        Map.of("id", id),
        rs -> rs.next() ? rs.getLong(1) : null
    );
  }

  private long sourceXmin(long id) {
    return jdbc.queryForObject(
        "select xmin::text::bigint from source.case_data where id = :id",
        Map.of("id", id),
        Long.class
    );
  }

  private String progressStatus() {
    return jdbc.queryForObject(
        "select status from ccd_data_migration.ccd_data_migration_progress where task_name = :taskName",
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

  private long sourceEventHwm() {
    return jdbc.queryForObject(
        "select source_event_hwm from ccd_data_migration.ccd_data_migration_progress where task_name = :taskName",
        Map.of("taskName", "ccd-data-migration"),
        Long.class
    );
  }

  private Long cutoverEventHwm() {
    return jdbc.queryForObject(
        "select cutover_event_hwm from ccd_data_migration.ccd_data_migration_progress where task_name = :taskName",
        Map.of("taskName", "ccd-data-migration"),
        Long.class
    );
  }

  private long significantItemsHwm() {
    return jdbc.queryForObject(
        "select significant_items_hwm from ccd_data_migration.ccd_data_migration_progress where task_name = :taskName",
        Map.of("taskName", "ccd-data-migration"),
        Long.class
    );
  }

  private long caseEventSequenceLastValue() {
    return jdbc.getJdbcTemplate().queryForObject("select last_value from ccd.case_event_id_seq", Long.class);
  }

  private long caseEventSignificantItemsSequenceLastValue() {
    return jdbc.getJdbcTemplate()
        .queryForObject("select last_value from ccd.case_event_significant_items_id_seq", Long.class);
  }

  private String significantItemDescription(long id) {
    return jdbc.queryForObject(
        "select description from ccd.case_event_significant_items where id = :id",
        Map.of("id", id),
        String.class
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

  private boolean fdwTableExists(String tableName) {
    return Boolean.TRUE.equals(jdbc.queryForObject(
        """
        select exists (
          select 1
          from pg_foreign_table ft
          join pg_class c on c.oid = ft.ftrelid
          join pg_namespace n on n.oid = c.relnamespace
          where n.nspname = 'fdw_stage'
            and c.relname = :tableName
        )
        """,
        Map.of("tableName", tableName),
        Boolean.class
    ));
  }

  private void createRole(String roleName) {
    Boolean exists = jdbc.queryForObject(
        """
        select exists (select 1 from pg_roles where rolname = :roleName)
        """,
        Map.of("roleName", roleName),
        Boolean.class
    );
    if (!Boolean.TRUE.equals(exists)) {
      jdbc.getJdbcTemplate().execute("create role " + quoteIdentifier(roleName));
    }
    jdbc.getJdbcTemplate().execute("grant " + quoteIdentifier(roleName) + " to current_user");
  }

  private long plannerRowEstimate(String tableName) {
    return jdbc.queryForObject(
        "select reltuples::bigint from pg_class where oid = to_regclass('ccd.' || :tableName)",
        Map.of("tableName", tableName),
        Long.class
    );
  }

  private String fdwServerOption(String optionName) {
    List<String> values = jdbc.queryForList(
        """
        select split_part(option, '=', 2)
        from pg_foreign_server s, unnest(coalesce(s.srvoptions, '{}')) option
        where s.srvname = 'ccd_migration_test_server'
          and split_part(option, '=', 1) = :optionName
        """,
        Map.of("optionName", optionName),
        String.class
    );
    return values.isEmpty() ? null : values.getFirst();
  }

  private void createFdwUserMapping(String roleName) {
    jdbc.getJdbcTemplate().execute("""
        create user mapping for """ + quoteIdentifier(roleName) + """
        server ccd_migration_test_server
        options (user 'test', password 'test', password_required 'false')
        """);
  }

  private long countFdwRowsAsRole(String roleName, String tableName) {
    if (!List.of("case_data", "case_event", "case_event_significant_items").contains(tableName)) {
      throw new IllegalArgumentException("Unexpected FDW table name: " + tableName);
    }

    return jdbc.getJdbcTemplate().execute((Connection connection) -> {
      try (var statement = connection.createStatement()) {
        statement.execute("set role " + quoteIdentifier(roleName));
        try {
          try (var resultSet = statement.executeQuery("select count(*) from fdw_stage." + tableName)) {
            resultSet.next();
            return resultSet.getLong(1);
          }
        } finally {
          statement.execute("reset role");
        }
      }
    });
  }

  private static String quoteIdentifier(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  private boolean caseDataTriggerEnabled(String triggerName) {
    List<Boolean> rows = jdbc.queryForList(
        """
        select tgenabled <> 'D'
        from pg_trigger
        where tgrelid = 'ccd.case_data'::regclass
          and tgname = :triggerName
        """,
        Map.of("triggerName", triggerName),
        Boolean.class
    );
    assertThat(rows).hasSize(1);
    return Boolean.TRUE.equals(rows.get(0));
  }

  private void assertElasticsearchQueueEmpty() {
    assertThat(countRows("ccd.es_queue")).isZero();
  }

  private void assertElasticsearchQueueEmpty(String caseTypeId) {
    assertThat(countElasticsearchQueueRows(caseTypeId)).isZero();
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
    return jdbc.queryForObject(
        "select localtimestamp - (:minutes * interval '1 minute')",
        Map.of("minutes", minutes),
        LocalDateTime.class
    );
  }

  @Configuration
  @ImportAutoConfiguration({
      DecentralisedFlywayAutoConfiguration.class,
      CcdDataMigrationAutoConfiguration.class,
      DataSourceAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class,
      JdbcTemplateAutoConfiguration.class,
      TransactionAutoConfiguration.class,
      FlywayAutoConfiguration.class
  })
  static class TestConfig {
  }

  // Moves an hour forward on every read, so any time limit is reached at its first check.
  private static final class HourlyTickingClock extends Clock {
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      Instant current = now;
      now = now.plus(Duration.ofHours(1));
      return current;
    }
  }
}

package uk.gov.hmcts.ccd.sdk.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
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
    jdbc.getJdbcTemplate().execute("drop table if exists ccd.ccd_data_migration_progress");
    jdbc.getJdbcTemplate().execute("truncate table ccd.case_event, ccd.case_data restart identity cascade");
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

    CcdDataMigrationRunResult secondRun = task.runMigration();
    assertThat(secondRun.batchesProcessed()).isEqualTo(1);
    assertThat(countRows("ccd.case_data")).isEqualTo(2);
    assertThat(countRows("ccd.case_event")).isEqualTo(3);
    assertThat(caseRevision(20)).isEqualTo(CASE_REVISION_OFFSET + 1);

    task.runMigration();
    CcdDataMigrationRunResult caughtUpRun = task.runMigration();
    assertThat(caughtUpRun.caughtUp()).isTrue();

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
        .maxRunTime(Duration.ofNanos(1))
        .build();

    CcdDataMigrationRunResult result = new TestMigrationTask(jdbc, transactionManager, options).runMigration();

    assertThat(result.batchesProcessed()).isEqualTo(1);
    assertThat(result.stoppedByTimeLimit()).isTrue();
    assertThat(countRows("ccd.case_data")).isEqualTo(1);
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
  void failsWithDocsLinkWhenFdwTablesAreMissing() {
    jdbc.getJdbcTemplate().execute("drop schema fdw_stage cascade");

    assertThatThrownBy(() -> task(10, 10).runMigration())
        .isInstanceOf(CcdDataMigrationException.class)
        .hasMessageContaining("FDW foreign tables are missing")
        .hasMessageContaining("https://github.com/hmcts/dtsse-ccd-config-generator/blob/master/docs/fdw-data-migration.md");
  }

  private CcdDataMigrationTask task(int batchSize, int maxBatchesPerRun) {
    var options = CcdDataMigrationTaskOptions.builder(List.of("TestCase"))
        .batchSize(batchSize)
        .maxBatchesPerRun(maxBatchesPerRun)
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
          id bigint not null unique,
          case_revision bigint not null
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
          proxied_by_last_name varchar(255),
          idempotency_key uuid,
          version integer,
          case_revision bigint
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
          id bigint,
          case_revision bigint
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
          proxied_by_last_name varchar(255),
          idempotency_key uuid,
          version integer,
          case_revision bigint
        )
        server ccd_migration_test_server
        options (schema_name 'source', table_name 'case_event')
        """);
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
          id,
          case_revision
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
          :id,
          :version
        )
        """,
        params
    );
  }

  private void insertSourceCaseEvent(long id, long caseDataId, String eventId, String stateId, String data) {
    var params = new MapSqlParameterSource()
        .addValue("id", id)
        .addValue("caseDataId", caseDataId)
        .addValue("eventId", eventId)
        .addValue("stateId", stateId)
        .addValue("data", data);
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
          proxied_by_last_name,
          idempotency_key,
          version,
          case_revision
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
        Map.of(
            "id", id,
            "version", version,
            "state", state,
            "data", data
        )
    );
  }

  private long countRows(String tableName) {
    return jdbc.getJdbcTemplate().queryForObject("select count(*) from " + tableName, Long.class);
  }

  private long caseRevision(long id) {
    return jdbc.queryForObject(
        "select case_revision from ccd.case_data where id = :id",
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

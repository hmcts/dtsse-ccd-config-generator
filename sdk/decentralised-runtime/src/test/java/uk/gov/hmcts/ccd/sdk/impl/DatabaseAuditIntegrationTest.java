package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;

@SpringBootTest(classes = DatabaseAuditIntegrationTest.TestConfig.class, properties = {
    "spring.datasource.url=jdbc:tc:postgresql:15-alpine:///ccd",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
})
class DatabaseAuditIntegrationTest {

  private static final long CASE_REFERENCE = 1_000_000_000_000_001L;
  private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private PlatformTransactionManager transactionManager;

  private final ObjectMapper mapper = new ObjectMapper();
  private TransactionTemplate transaction;

  @BeforeEach
  void setUp() {
    transaction = new TransactionTemplate(transactionManager);
    jdbc.execute("drop table if exists public.audit_test");
    jdbc.execute("truncate table ccd.case_data cascade");
    jdbc.execute("""
        create table public.audit_test (
          id bigint primary key,
          note text not null,
          enabled boolean not null,
          metadata json not null
        )
        """);
    jdbc.execute("""
        create trigger ccd_audit_row_changes
        after insert or update or delete on public.audit_test
        for each row execute function ccd.audit_row_change()
        """);
    insertCase();
  }

  @Test
  void recordsCompleteRowsForInsertUpdateDeleteAndNoOpUpdates() throws Exception {
    long caseEventId = transaction.execute(status -> {
      long reservedId = reserveEventContext();
      jdbc.update("""
          insert into audit_test(id, note, enabled, metadata)
          values (1, 'before', true, '{"source":"test"}'::json)
          """);
      jdbc.update("update audit_test set note = 'after' where id = 1");
      jdbc.update("update audit_test set note = 'after' where id = 1");
      jdbc.update("delete from audit_test where id = 1");
      insertEvent(reservedId, 1);
      return reservedId;
    });

    List<AuditRow> rows = loadAuditRows(caseEventId);
    assertThat(rows).extracting(AuditRow::operation)
        .containsExactly("INSERT", "UPDATE", "UPDATE", "DELETE");

    assertThat(rows.get(0).oldValues()).isNull();
    assertThat(json(rows.get(0).newValues()))
        .containsEntry("id", 1)
        .containsEntry("note", "before")
        .containsEntry("enabled", true)
        .containsEntry("metadata", Map.of("source", "test"));

    assertThat(json(rows.get(1).oldValues())).containsEntry("note", "before");
    assertThat(json(rows.get(1).newValues())).containsEntry("note", "after");

    assertThat(json(rows.get(2).oldValues())).isEqualTo(json(rows.get(2).newValues()));

    assertThat(json(rows.get(3).oldValues())).containsEntry("note", "after");
    assertThat(rows.get(3).newValues()).isNull();
  }

  @Test
  void predicateBasedUpdateCreatesOneEntryPerAffectedRow() {
    long caseEventId = transaction.execute(status -> {
      long reservedId = reserveEventContext();
      jdbc.update("""
          insert into audit_test(id, note, enabled, metadata) values
            (1, 'one', true, '{}'::json),
            (2, 'two', true, '{}'::json)
          """);
      jdbc.update("update audit_test set enabled = false where enabled = true");
      insertEvent(reservedId, 1);
      return reservedId;
    });

    Integer updates = jdbc.queryForObject(
        "select count(*) from ccd.audit_log where case_event_id = ? and operation = 'UPDATE'",
        Integer.class,
        caseEventId
    );
    assertThat(updates).isEqualTo(2);
  }

  @Test
  void rejectsAuditedWritesWithoutCaseEventContext() {
    assertThatThrownBy(() -> jdbc.update(
        "insert into audit_test(id, note, enabled, metadata) values (1, 'outside event', true, '{}'::json)"
    ))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("requires a CCD case event context");

    assertThat(jdbc.queryForObject("select count(*) from audit_test", Integer.class)).isZero();
  }

  @Test
  void maintenanceBypassAllowsWriteWithoutCreatingAuditHistory() {
    transaction.executeWithoutResult(status -> {
      jdbc.queryForObject(
          "select set_config('ccd.audit_disabled', 'true', true)",
          String.class
      );
      jdbc.update("""
          insert into audit_test(id, note, enabled, metadata)
          values (1, 'maintenance', true, '{}'::json)
          """);
    });

    assertThat(jdbc.queryForObject("select count(*) from audit_test", Integer.class)).isOne();
    assertThat(jdbc.queryForObject("select count(*) from ccd.audit_log", Integer.class)).isZero();
  }

  @Test
  void unresolvedCaseEventReferenceRollsBackAtCommit() {
    assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
      reserveEventContext();
      jdbc.update("""
          insert into audit_test(id, note, enabled, metadata)
          values (1, 'orphaned', true, '{}'::json)
          """);
    })).isInstanceOf(DataIntegrityViolationException.class);

    assertThat(jdbc.queryForObject("select count(*) from audit_test", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from ccd.audit_log", Integer.class)).isZero();
  }

  private void insertCase() {
    jdbc.update("""
        insert into ccd.case_data (
          id, reference, version, security_classification, jurisdiction, case_type_id, state, data
        ) values (?, ?, 1, 'PUBLIC', 'TEST', 'AuditTest', 'Created', '{}'::jsonb)
        """, CASE_REFERENCE, CASE_REFERENCE);
  }

  private long reserveEventContext() {
    return jdbc.queryForObject(
        """
        select set_config(
            'ccd.case_event_id',
            nextval('ccd.case_event_id_seq')::text,
            true
        )::bigint
        """,
        Long.class
    );
  }

  private void insertEvent(long caseEventId, long caseRevision) {
    jdbc.update("""
        insert into ccd.case_event (
          id, security_classification, case_data_id, case_type_version, event_id, user_id,
          case_type_id, state_id, data, user_first_name, user_last_name, event_name,
          state_name, version, case_revision, idempotency_key
        ) values (
          ?, 'PUBLIC', ?, 1, 'audit-test', 'user-1', 'AuditTest', 'Created', '{}'::jsonb,
          'Test', 'User', 'Audit test', 'Created', 1, ?, ?
        )
        """, caseEventId, CASE_REFERENCE, caseRevision, UUID.randomUUID());
  }

  private List<AuditRow> loadAuditRows(long caseEventId) {
    return jdbc.query(
        """
        select operation::text, old_values::text, new_values::text
        from ccd.audit_log
        where case_event_id = ?
        order by id
        """,
        (rs, rowNum) -> new AuditRow(
            rs.getString("operation"),
            rs.getString("old_values"),
            rs.getString("new_values")
        ),
        caseEventId
    );
  }

  private Map<String, Object> json(String value) throws Exception {
    return mapper.readValue(value, JSON_MAP);
  }

  private record AuditRow(String operation, String oldValues, String newValues) {}

  @Configuration
  @ImportAutoConfiguration({
      DecentralisedFlywayAutoConfiguration.class,
      DataSourceAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class,
      JdbcTemplateAutoConfiguration.class,
      TransactionAutoConfiguration.class,
      FlywayAutoConfiguration.class
  })
  static class TestConfig {
  }
}

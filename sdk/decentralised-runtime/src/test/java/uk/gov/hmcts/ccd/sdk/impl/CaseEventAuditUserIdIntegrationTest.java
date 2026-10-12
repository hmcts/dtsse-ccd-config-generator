package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;

@SpringBootTest(classes = CaseEventAuditUserIdIntegrationTest.TestConfig.class, properties = {
    "spring.datasource.url=jdbc:tc:postgresql:15-alpine:///ccd",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
})
class CaseEventAuditUserIdIntegrationTest {

  private static final long CASE_REFERENCE = 1_000_000_000_000_002L;

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Test
  void auditsCaseEventUpdatesForLegacyNonUuidIdamIds() {
    jdbc.execute("truncate table ccd.case_data cascade");
    jdbc.update("""
        insert into ccd.case_data (
          id, reference, version, security_classification, jurisdiction, case_type_id, state, data
        ) values (?, ?, 1, 'PUBLIC', 'TEST', 'AuditTest', 'Created', '{}'::jsonb)
        """, CASE_REFERENCE, CASE_REFERENCE);
    jdbc.update("""
        insert into ccd.case_event (
          id, security_classification, case_data_id, case_type_version, event_id, user_id,
          case_type_id, state_id, data, user_first_name, user_last_name, event_name,
          state_name, version, case_revision, idempotency_key
        ) values (
          1, 'PUBLIC', ?, 1, 'audit-test', '12345', 'AuditTest', 'Created', '{}'::jsonb,
          'Test', 'User', 'Audit test', 'Created', 1, 1, ?
        )
        """, CASE_REFERENCE, UUID.randomUUID());

    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      jdbc.queryForObject("select set_config('ccd.user_idam_id', '12345', true)", String.class);
      jdbc.update("update ccd.case_event set data = '{\"changed\": true}'::jsonb where id = 1");
    });

    assertThat(jdbc.queryForObject(
        "select user_id from ccd.case_event_audit where case_event_id = 1", String.class))
        .isEqualTo("12345");
  }

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

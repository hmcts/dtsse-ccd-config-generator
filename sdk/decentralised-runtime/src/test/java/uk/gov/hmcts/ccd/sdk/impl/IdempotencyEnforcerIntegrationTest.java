package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;

@SpringBootTest(classes = IdempotencyEnforcerIntegrationTest.TestConfig.class, properties = {
    "spring.datasource.url=jdbc:tc:postgresql:15-alpine:///ccd",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
})
class IdempotencyEnforcerIntegrationTest {

  private static final long CASE_ID = 9876L;
  private static final long CASE_REFERENCE = 9999000000009876L;
  private static final String BLOCKED_REQUEST_APPLICATION_NAME = "idempotency-enforcer-concurrent-request";

  @Autowired
  private IdempotencyEnforcer idempotencyEnforcer;

  @Autowired
  private NamedParameterJdbcTemplate jdbc;

  @Autowired
  private PlatformTransactionManager transactionManager;

  private TransactionTemplate transaction;

  @BeforeEach
  void setUp() {
    transaction = new TransactionTemplate(transactionManager);
    seedCaseData();
  }

  @Test
  void seesEventCommittedWhileWaitingForCaseLock() throws Exception {
    UUID idempotencyKey = UUID.randomUUID();
    var caseLocked = new CountDownLatch(1);
    var commitFirstRequest = new CountDownLatch(1);
    var eventId = new AtomicLong();
    var executor = Executors.newFixedThreadPool(2);

    try {
      var firstRequest = executor.submit(() -> transaction.executeWithoutResult(status -> {
        assertThat(idempotencyEnforcer.lockCaseAndGetExistingEvent(idempotencyKey, CASE_REFERENCE))
            .isEmpty();
        caseLocked.countDown();
        await(commitFirstRequest);
        eventId.set(insertEvent(idempotencyKey));
      }));

      assertThat(caseLocked.await(10, TimeUnit.SECONDS)).isTrue();

      var secondRequest = executor.submit(() -> transaction.execute(status -> {
        jdbc.getJdbcTemplate().execute(
            "set local application_name = '" + BLOCKED_REQUEST_APPLICATION_NAME + "'"
        );
        return idempotencyEnforcer.lockCaseAndGetExistingEvent(idempotencyKey, CASE_REFERENCE);
      }));

      waitUntilSecondRequestIsBlocked();
      commitFirstRequest.countDown();

      firstRequest.get(10, TimeUnit.SECONDS);
      assertThat(secondRequest.get(10, TimeUnit.SECONDS)).contains(eventId.get());
    } finally {
      commitFirstRequest.countDown();
      executor.shutdownNow();
    }
  }

  private void waitUntilSecondRequestIsBlocked() throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      Integer blockedConnections = jdbc.queryForObject(
          """
          select count(*)
          from pg_stat_activity
          where application_name = :applicationName
            and wait_event_type = 'Lock'
          """,
          Map.of("applicationName", BLOCKED_REQUEST_APPLICATION_NAME),
          Integer.class
      );
      if (blockedConnections != null && blockedConnections > 0) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("Second request did not block while acquiring the case lock");
  }

  private void seedCaseData() {
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
          supplementary_data,
          security_classification,
          case_revision,
          created_date,
          last_modified,
          last_state_modified_date
        ) values (
          :id,
          :reference,
          1,
          'TEST',
          'TestCase',
          'Submitted',
          '{}'::jsonb,
          '{}'::jsonb,
          'PUBLIC',
          1,
          now(),
          now(),
          now()
        )
        """,
        Map.of("id", CASE_ID, "reference", CASE_REFERENCE)
    );
  }

  private long insertEvent(UUID idempotencyKey) {
    var params = new MapSqlParameterSource()
        .addValue("case_data_id", CASE_ID)
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
          1,
          'event',
          'summary',
          'description',
          'user',
          'TestCase',
          'Submitted',
          '{}'::jsonb,
          'Test',
          'User',
          'Event',
          'Submitted',
          'PUBLIC'::ccd.securityclassification,
          1,
          1,
          :idempotency_key
        )
        returning id
        """,
        params,
        Long.class
    );
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting to complete the concurrent request");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting to complete the concurrent request", e);
    }
  }

  @Configuration
  @Import(IdempotencyEnforcer.class)
  @ImportAutoConfiguration({
      DecentralisedFlywayAutoConfiguration.class,
      DataSourceAutoConfiguration.class,
      JdbcTemplateAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class,
      TransactionAutoConfiguration.class,
      FlywayAutoConfiguration.class
  })
  static class TestConfig {
  }
}

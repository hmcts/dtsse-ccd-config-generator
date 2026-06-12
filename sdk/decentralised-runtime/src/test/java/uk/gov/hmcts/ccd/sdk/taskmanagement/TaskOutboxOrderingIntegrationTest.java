package uk.gov.hmcts.ccd.sdk.taskmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
import uk.gov.hmcts.ccd.sdk.config.DecentralisedDataConfiguration;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskAction;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TaskOutboxRecord;

@SpringBootTest(
    classes = TaskOutboxOrderingIntegrationTest.TestConfig.class,
    properties = {
        "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///ccd",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
    }
)
class TaskOutboxOrderingIntegrationTest {

  private static final long CASE_A = 9911000000000001L;
  private static final long CASE_B = 9911000000000002L;
  private static final LocalDateTime FIRST_TRIGGER = LocalDateTime.of(2026, 6, 11, 10, 0);
  private static final LocalDateTime SECOND_TRIGGER = LocalDateTime.of(2026, 6, 11, 11, 0);

  @Autowired
  private NamedParameterJdbcTemplate jdbc;

  private TaskOutboxRepository repository;

  @BeforeEach
  void setUp() {
    repository = new TaskOutboxRepository(jdbc, new TaskManagementProperties());
    jdbc.update(
        "delete from ccd.task_outbox where case_id in (:caseA, :caseB)",
        Map.of("caseA", CASE_A, "caseB", CASE_B)
    );
    insertCase(CASE_A);
    insertCase(CASE_B);
  }

  @Test
  void claimsActionsInRequiredOrderRegardlessOfInsertionOrder() {
    enqueue(CASE_A, "event-one", FIRST_TRIGGER, TaskAction.INITIATE);
    enqueue(CASE_A, "event-one", FIRST_TRIGGER, TaskAction.RECONFIGURE);
    enqueue(CASE_A, "event-one", FIRST_TRIGGER, TaskAction.CANCEL);

    TaskOutboxRecord cancellation = claimSingle();
    assertThat(cancellation.requestedAction()).isEqualTo(TaskAction.CANCEL.getId());
    repository.markProcessed(cancellation.id(), 200);

    TaskOutboxRecord reconfiguration = claimSingle();
    assertThat(reconfiguration.requestedAction()).isEqualTo(TaskAction.RECONFIGURE.getId());
    repository.markProcessed(reconfiguration.id(), 200);

    TaskOutboxRecord initiation = claimSingle();
    assertThat(initiation.requestedAction()).isEqualTo(TaskAction.INITIATE.getId());
  }

  @Test
  void claimsEarlierTriggerBeforeLowerIdFromLaterTrigger() {
    enqueue(CASE_A, "later-event", SECOND_TRIGGER, TaskAction.INITIATE);
    enqueue(CASE_A, "earlier-event", FIRST_TRIGGER, TaskAction.INITIATE);

    TaskOutboxRecord claimed = claimSingle();

    assertThat(claimed.eventId()).isEqualTo("earlier-event");
    assertThat(claimed.created()).isEqualTo(FIRST_TRIGGER);
  }

  @Test
  void failedPredecessorBlocksLaterActionInSameTrigger() {
    long cancellationId = enqueue(CASE_A, "event-one", FIRST_TRIGGER, TaskAction.CANCEL);
    enqueue(CASE_A, "event-one", FIRST_TRIGGER, TaskAction.INITIATE);
    enqueue(CASE_A, "event-two", SECOND_TRIGGER, TaskAction.INITIATE);
    jdbc.update(
        """
            update ccd.task_outbox
            set status = 'FAILED'::ccd.task_outbox_status,
                next_attempt_at = (current_timestamp at time zone 'UTC') + interval '1 hour'
            where id = :id
            """,
        Map.of("id", cancellationId)
    );

    assertThat(repository.claimPending(10, 0)).isEmpty();
  }

  @Test
  void failedEarlierTriggerBlocksLaterTriggerEvenWhenRetryIsNotDue() {
    long failedId = enqueue(CASE_A, "event-one", FIRST_TRIGGER, TaskAction.INITIATE);
    enqueue(CASE_A, "event-two", SECOND_TRIGGER, TaskAction.INITIATE);
    setFailureStatus(failedId, "FAILED");

    assertThat(repository.claimPending(10, 9)).isEmpty();
  }

  @Test
  void unprocessableEarlierTriggerBlocksLaterTrigger() {
    long unprocessableId = enqueue(CASE_A, "event-one", FIRST_TRIGGER, TaskAction.INITIATE);
    enqueue(CASE_A, "event-two", SECOND_TRIGGER, TaskAction.INITIATE);
    setFailureStatus(unprocessableId, "UNPROCESSABLE");

    assertThat(repository.claimPending(10, 9)).isEmpty();
  }

  @Test
  void dueFailedRowRemainsEligibleForRetry() {
    long failedId = enqueue(CASE_A, "event-one", FIRST_TRIGGER, TaskAction.INITIATE);
    jdbc.update(
        """
            update ccd.task_outbox
            set status = 'FAILED'::ccd.task_outbox_status,
                next_attempt_at = (current_timestamp at time zone 'UTC') - interval '1 second'
            where id = :id
            """,
        Map.of("id", failedId)
    );

    assertThat(claimSingle().id()).isEqualTo(failedId);
  }

  @Test
  void futureWaitingTriggerDoesNotBlockLaterTrigger() {
    long waitingId = enqueue(CASE_A, "delayed-event", FIRST_TRIGGER, TaskAction.INITIATE);
    enqueue(CASE_A, "later-event", SECOND_TRIGGER, TaskAction.INITIATE);
    jdbc.update(
        """
            update ccd.task_outbox
            set status = 'WAITING'::ccd.task_outbox_status,
                next_attempt_at = (current_timestamp at time zone 'UTC') + interval '1 hour'
            where id = :id
            """,
        Map.of("id", waitingId)
    );

    TaskOutboxRecord claimed = claimSingle();

    assertThat(claimed.eventId()).isEqualTo("later-event");
  }

  @Test
  void claimsOneRowForEachIndependentCase() {
    enqueue(CASE_A, "event-one", FIRST_TRIGGER, TaskAction.CANCEL);
    enqueue(CASE_B, "event-one", FIRST_TRIGGER, TaskAction.CANCEL);

    List<TaskOutboxRecord> claimed = repository.claimPending(10, 0);

    assertThat(claimed).extracting(TaskOutboxRecord::caseId).containsExactlyInAnyOrder(CASE_A, CASE_B);
  }

  @Test
  void rejectsMissingEventId() {
    assertThatThrownBy(() -> insertOutboxRow(null))
        .hasMessageContaining("event_id");
  }

  @Test
  void rejectsBlankEventId() {
    assertThatThrownBy(() -> insertOutboxRow(" "))
        .hasMessageContaining("task_outbox_event_id_not_blank");
  }

  private TaskOutboxRecord claimSingle() {
    List<TaskOutboxRecord> claimed = repository.claimPending(10, 0);
    assertThat(claimed).hasSize(1);
    return claimed.get(0);
  }

  private long enqueue(long caseId, String eventId, LocalDateTime created, TaskAction action) {
    return repository.enqueueAndReturnId(
        String.valueOf(caseId),
        eventId,
        created,
        "{}",
        action.getId(),
        null
    );
  }

  private void insertCase(long caseReference) {
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
            )
            values (
              :reference,
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
            on conflict (reference) do nothing
            """,
        Map.of("reference", caseReference)
    );
  }

  private void insertOutboxRow(String eventId) {
    jdbc.update(
        """
            insert into ccd.task_outbox (case_id, event_id, payload, requested_action)
            values (:caseId, :eventId, '{}'::jsonb, 'initiate'::ccd.task_action)
            """,
        new MapSqlParameterSource()
            .addValue("caseId", CASE_A)
            .addValue("eventId", eventId)
    );
  }

  private void setFailureStatus(long id, String status) {
    jdbc.update(
        """
            update ccd.task_outbox
            set status = cast(:status as ccd.task_outbox_status),
                next_attempt_at = (current_timestamp at time zone 'UTC') + interval '1 hour'
            where id = :id
            """,
        Map.of("id", id, "status", status)
    );
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

package uk.gov.hmcts.ccd.sdk.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.gov.hmcts.ccd.sdk.SystemEventAction;
import uk.gov.hmcts.ccd.sdk.SystemEventExecutionResult;
import uk.gov.hmcts.ccd.sdk.SystemEventExecutor;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;

@Testcontainers
class CaseJobRunnerTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static DataSource dataSource;
  private static NamedParameterJdbcTemplate jdbc;

  private final LockingSystemEvents systemEvents = new LockingSystemEvents(jdbc,
      new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
  private final CaseJobRunner runner = runner();

  @BeforeAll
  static void migrate() {
    dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DecentralisedFlywayAutoConfiguration.class,
            JobUtilsFlywayAutoConfiguration.class, FlywayAutoConfiguration.class))
        .withBean(DataSource.class, () -> dataSource)
        .run(context -> assertThat(context).hasNotFailed());
    jdbc = new NamedParameterJdbcTemplate(dataSource);
  }

  @BeforeEach
  void cases() {
    jdbc.update("truncate ccd.case_data cascade", Map.of());
    jdbc.update("truncate job_utils.case_job_run", Map.of());
    jdbc.update("drop table if exists done", Map.of());
    jdbc.update("create table done (reference bigint, state text)", Map.of());
    jdbc.update("drop table if exists events", Map.of());
    jdbc.update("create table events (reference bigint, event_id text, summary text)", Map.of());
    insertCase(3, "PCS", "Open");
    insertCase(1, "PCS", "Open");
    insertCase(2, "PCS", "Closed");
    insertCase(4, "OTHER", "Open");
  }

  @Test
  void aRunDoesTheJobOnceToEachCaseOfItsCaseTypesEachInAnEventOfItsOwn() {
    Job job = new Job(reference -> { });
    runner.run(job, "1");
    runner.run(job, "1");

    assertThat(done()).containsExactly(1L, 3L);
    assertThat(jdbc.queryForList("select reference || ' ' || event_id || ' ' || summary from events",
        Map.of(), String.class)).containsExactly("1 mark-cases Run 1", "3 mark-cases Run 1");
    assertThat(jdbc.queryForMap("select applied, skipped from job_utils.case_job_run", Map.of()))
        .containsEntry("applied", 2L).containsEntry("skipped", 1L);
  }

  @Test
  void aSkippedCaseIsLeftAsItWasWithNoEvent() {
    runner.run(new Job(reference -> { }), "1");

    // The job wrote to case 2, which is closed, before skipping it.
    assertThat(done()).doesNotContain(2L);
    assertThat(jdbc.queryForList("select reference from events", Map.of(), Long.class)).doesNotContain(2L);
  }

  @Test
  void theJobReadsAndWritesWithTheCaseLocked() {
    List<Boolean> locked = new ArrayList<>();
    runner.run(new Job(reference -> locked.add(lockedElsewhere(reference))), "1");

    assertThat(locked).containsExactly(true, true, true);
  }

  @Test
  void aCaseDeletedJustBeforeItsEventIsPassedOver() {
    // Retention deletes case 1 after the runner checks it exists but before its event locks it.
    SystemEventExecutor deletingFirst = new LockingSystemEvents(jdbc,
        new TransactionTemplate(new DataSourceTransactionManager(dataSource))) {
      @Override
      public SystemEventExecutionResult execute(long caseReference, UUID idempotencyKey, SystemEventAction action) {
        if (caseReference == 1) {
          jdbc.update("delete from ccd.case_data where reference = 1", Map.of());
        }
        return super.execute(caseReference, idempotencyKey, action);
      }
    };
    new CaseJobRunner(new NamedParameterJdbcTemplate(dataSource), deletingFirst, Duration.ofMinutes(5), 2)
        .run(new Job(reference -> { }), "1");

    assertThat(done()).containsExactly(3L);
    assertThat(jdbc.queryForMap("select applied, skipped, failed_at from job_utils.case_job_run", Map.of()))
        .containsEntry("applied", 1L).containsEntry("skipped", 2L).containsEntry("failed_at", null);
  }

  @Test
  void aNewRunIdRunsTheJobAgain() {
    Job job = new Job(reference -> { });
    runner.run(job, "1");
    runner.run(job, "2");

    assertThat(done()).containsExactly(1L, 3L, 1L, 3L);
  }

  @Test
  void aCaseDoneAgainInTheSameRunIsNotChangedTwice() {
    Job job = new Job(reference -> { });
    runner.run(job, "1");
    // As if the instance stopped after each case's event but before recording it as done.
    jdbc.update("truncate job_utils.case_job_run", Map.of());
    runner.run(job, "1");

    assertThat(done()).containsExactly(1L, 3L);
    // Replayed cases move the run on without being counted again, so it finishes.
    assertThat(jdbc.queryForMap("select applied, finished_at is not null as finished from job_utils.case_job_run",
        Map.of())).containsEntry("applied", 0L).containsEntry("finished", true);
  }

  @Test
  void aStoppedRunIsCarriedOnFromTheCaseAfterItsLast() {
    runner.run(new Job(reference -> runner.stop()), "1");
    assertThat(done()).containsExactly(1L);

    runner().run(new Job(reference -> { }), "1");
    assertThat(done()).containsExactly(1L, 3L);
  }

  @Test
  void aRunAnotherInstanceHoldsIsLeftToIt() {
    List<Long> doneByOther = new ArrayList<>();
    CaseJobRunner other = runner();
    runner.run(new Job(reference -> {
      if (reference == 1) {
        other.run(new Job(doneByOther::add), "1");
      }
    }), "1");

    assertThat(doneByOther).isEmpty();
    assertThat(done()).containsExactly(1L, 3L);
  }

  @Test
  void anInstanceThatLostTheRunCommitsNothingForTheCaseItWasOn() {
    runner.run(new Job(reference -> {
      if (reference == 3) {
        jdbc.getJdbcTemplate().execute("update job_utils.case_job_run set lease_token = gen_random_uuid()");
      }
    }), "1");

    // Case 3's note, event and its recording as done were rolled back together.
    assertThat(done()).containsExactly(1L);
    assertThat(jdbc.queryForList("select reference from events", Map.of(), Long.class)).containsExactly(1L);
  }

  @Test
  void aFailedCaseIsTriedAgainAtTheNextTryAndTheRunCarriesOnFromIt() {
    List<Long> attempts = new ArrayList<>();
    Job failingOnce = new Job(reference -> {
      attempts.add(reference);
      if (reference == 3 && attempts.stream().filter(r -> r == 3).count() == 1) {
        throw new IllegalStateException("lock timeout", new IllegalArgumentException("underneath"));
      }
    });
    runner.run(failingOnce, "1");

    assertThat(done()).containsExactly(1L);
    assertThat(jdbc.queryForObject("select failure from job_utils.case_job_run", Map.of(), String.class))
        .contains("lock timeout").contains("caused by").contains("underneath");

    runner.run(failingOnce, "1");
    assertThat(done()).containsExactly(1L, 3L);
    assertThat(attempts).containsExactly(1L, 2L, 3L, 3L);
    assertThat(jdbc.queryForObject("select finished_at is not null from job_utils.case_job_run", Map.of(),
        Boolean.class)).isTrue();
  }

  @Test
  void aStoppedRunnerClaimsNothing() {
    runner.stop();
    runner.run(new Job(reference -> { }), "1");

    assertThat(jdbc.queryForObject("select count(*) from job_utils.case_job_run", Map.of(), Integer.class))
        .isZero();
  }

  @Test
  void anErrorDoesNotStopAnInstanceTrying() throws Exception {
    List<Long> attempts = new ArrayList<>();
    CaseJobsAutoConfiguration.CaseJobs jobs = new CaseJobsAutoConfiguration.CaseJobs(this::runner,
        Map.of(new Job(reference -> {
          attempts.add(reference);
          if (attempts.size() == 1) {
            throw new AssertionError("an Error, not an exception");
          }
        }), "1"), Duration.ofMillis(50));
    jobs.start();
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (done().size() < 2 && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    jobs.stop();

    assertThat(done()).containsExactly(1L, 3L);
  }

  @Test
  void anApplicationStoppedAndStartedAgainCarriesItsRunsOn() throws Exception {
    CountDownLatch onFirstCase = new CountDownLatch(1);
    CountDownLatch stopping = new CountDownLatch(1);
    CaseJobsAutoConfiguration.CaseJobs jobs = new CaseJobsAutoConfiguration.CaseJobs(this::runner,
        Map.of(new Job(reference -> {
          if (reference == 1) {
            onFirstCase.countDown();
            await(stopping);
          }
        }), "1"), Duration.ofMillis(50));

    jobs.start();
    await(onFirstCase);
    Thread stop = new Thread(jobs::stop);
    stop.start();
    stopping.countDown();
    stop.join();
    assertThat(done()).containsExactly(1L);

    jobs.start();
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (done().size() < 2 && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    jobs.stop();
    assertThat(done()).containsExactly(1L, 3L);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(20, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void anEnvironmentRunsTheJobsItSetsARunIdFor() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CaseJobsAutoConfiguration.class))
        .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
            new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("CCD_JOBS_RUN_MARK_CASES", "2026-11-27"))))
        .withBean(DataSource.class, () -> dataSource)
        .withBean(SystemEventExecutor.class, () -> systemEvents)
        .withBean("marks", CaseJob.class, () -> new Job(reference -> { }))
        .withBean("unset", CaseJob.class, () -> new Job(reference -> { }) {
          @Override
          public String name() {
            return "not-run";
          }
        })
        .run(context -> {
          long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
          while (done().size() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(50);
          }
          assertThat(done()).containsExactly(1L, 3L);
          assertThat(jdbc.queryForList("select job_name from job_utils.case_job_run", Map.of(),
              String.class)).containsExactly("mark-cases");
        });
  }

  @Test
  void aJobCannotRunWithoutASystemUser() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CaseJobsAutoConfiguration.class))
        .withPropertyValues("ccd.jobs.run.mark-cases=1")
        .withBean(DataSource.class, () -> dataSource)
        .withBean(CaseJob.class, () -> new Job(reference -> { }))
        .run(context -> assertThat(context).hasFailed().getFailure()
            .rootCause().hasMessageContaining("ccd.decentralised-runtime.system-user.id"));
  }

  @Test
  void aJobMustNameItsCaseTypes() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CaseJobsAutoConfiguration.class))
        .withBean(DataSource.class, () -> dataSource)
        .withBean(CaseJob.class, () -> new Job(reference -> { }) {
          @Override
          public Set<String> caseTypes() {
            return Set.of();
          }
        })
        .run(context -> assertThat(context).hasFailed());
  }

  private CaseJobRunner runner() {
    return new CaseJobRunner(new NamedParameterJdbcTemplate(dataSource), systemEvents, Duration.ofMinutes(5), 2);
  }

  /**
   * Records each case it is applied to, in the case's event, then does {@code also}; skips closed cases, after
   * recording them.
   */
  private static class Job implements CaseJob {
    private final LongConsumer also;

    Job(LongConsumer also) {
      this.also = also;
    }

    @Override
    public String name() {
      return "mark-cases";
    }

    @Override
    public Set<String> caseTypes() {
      return Set.of("PCS");
    }

    @Override
    public CaseJobOutcome apply(CaseMetadata metadata) {
      jdbc.update("insert into done values (:case, :state)",
          Map.of("case", metadata.reference(), "state", metadata.state()));
      also.accept(metadata.reference());
      return "Closed".equals(metadata.state()) ? CaseJobOutcome.skipped() : CaseJobOutcome.applied();
    }
  }

  private static boolean lockedElsewhere(long reference) {
    try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
      statement.execute("set lock_timeout = '100ms'");
      statement.execute("select reference from ccd.case_data where reference = " + reference + " for update");
      return false;
    } catch (SQLException e) {
      return true;
    }
  }

  private static List<Long> done() {
    return jdbc.queryForList("select reference from done", Map.of(), Long.class);
  }

  private static void insertCase(long reference, String caseType, String state) {
    jdbc.update("""
        insert into ccd.case_data
          (id, reference, case_revision, security_classification, jurisdiction, case_type_id, state, data)
        values (:ref, :ref, 1, 'PUBLIC', 'PCS', :type, :state, '{}'::jsonb)
        """, Map.of("ref", reference, "type", caseType, "state", state));
  }
}

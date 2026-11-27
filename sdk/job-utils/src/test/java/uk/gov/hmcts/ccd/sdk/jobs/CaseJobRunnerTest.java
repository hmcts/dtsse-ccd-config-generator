package uk.gov.hmcts.ccd.sdk.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;

@Testcontainers
class CaseJobRunnerTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static DataSource dataSource;
  private static NamedParameterJdbcTemplate jdbc;

  private final TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
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
    insertCase(3, "PCS", "Open");
    insertCase(1, "PCS", "Open");
    insertCase(2, "PCS", "Closed");
    insertCase(4, "OTHER", "Open");
  }

  @Test
  void aRunDoesTheJobOnceToEachCaseOfItsCaseTypes() {
    Job job = new Job(reference -> { });
    runner.run(job, "1");
    runner.run(job, "1");

    assertThat(done()).containsExactly(1L, 2L, 3L);
    assertThat(jdbc.queryForList("select state from done", Map.of(), String.class))
        .containsExactly("Open", "Closed", "Open");
    assertThat(jdbc.queryForMap("select applied, skipped from job_utils.case_job_run", Map.of()))
        .containsEntry("applied", 2L).containsEntry("skipped", 1L);
  }

  @Test
  void aNewRunIdRunsTheJobAgain() {
    Job job = new Job(reference -> { });
    runner.run(job, "1");
    runner.run(job, "2");

    assertThat(done()).containsExactly(1L, 2L, 3L, 1L, 2L, 3L);
  }

  @Test
  void aStoppedRunIsCarriedOnFromTheCaseAfterItsLast() {
    runner.run(new Job(reference -> runner.stop()), "1");
    assertThat(done()).containsExactly(1L);

    runner().run(new Job(reference -> { }), "1");
    assertThat(done()).containsExactly(1L, 2L, 3L);
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
    assertThat(done()).containsExactly(1L, 2L, 3L);
  }

  @Test
  void aRunTakenOverByAnotherInstanceRollsBackItsCaseAndStops() {
    runner.run(new Job(reference -> {
      if (reference == 2) {
        jdbc.getJdbcTemplate().execute("update job_utils.case_job_run set lease_token = gen_random_uuid()");
      }
    }), "1");

    assertThat(done()).containsExactly(1L);
  }

  @Test
  void aFailedRunStopsAtTheCaseThatFailedAndIsNotRetried() {
    Job failing = new Job(reference -> {
      if (reference == 2) {
        throw new IllegalStateException("no");
      }
    });
    runner.run(failing, "1");
    runner.run(new Job(reference -> { }), "1");

    assertThat(done()).containsExactly(1L);
    assertThat(jdbc.queryForObject("select failed_reference from job_utils.case_job_run", Map.of(),
        Long.class)).isEqualTo(2L);
  }

  @Test
  void theJobRunsWithTheCaseLockedUnlessItOnlyReads() {
    List<Boolean> locked = new ArrayList<>();
    LongConsumer checkLock = reference -> locked.add(lockedElsewhere(reference));
    runner.run(new Job(checkLock), "1");
    runner.run(new Job(checkLock) {
      @Override
      public ApplyIn applyIn() {
        return ApplyIn.TRANSACTION;
      }
    }, "2");

    assertThat(locked).containsExactly(true, true, true, false, false, false);
  }

  @Test
  void aJobThatRunsSystemEventsIsAppliedOutsideATransactionWithAKeyForEachCaseAndRun() {
    List<Boolean> inTransaction = new ArrayList<>();
    List<UUID> keys = new ArrayList<>();
    class OwnTransactions extends Job {
      OwnTransactions() {
        super(reference -> inTransaction.add(TransactionSynchronizationManager.isActualTransactionActive()));
      }

      @Override
      public ApplyIn applyIn() {
        return ApplyIn.NO_TRANSACTION;
      }

      @Override
      public CaseJobOutcome apply(CaseMetadata metadata) {
        keys.add(metadata.idempotencyKey());
        return super.apply(metadata);
      }
    }

    runner.run(new OwnTransactions(), "1");
    jdbc.update("truncate job_utils.case_job_run", Map.of());
    runner.run(new OwnTransactions(), "1");
    runner.run(new OwnTransactions(), "2");

    assertThat(inTransaction).containsOnly(false).hasSize(9);
    // Applied again in the same run, a case gets the same key; in another run, a new one.
    assertThat(keys.subList(0, 3)).doesNotHaveDuplicates().isEqualTo(keys.subList(3, 6));
    assertThat(keys.subList(6, 9)).doesNotContainAnyElementsOf(keys.subList(0, 3));
  }

  @Test
  void anEnvironmentRunsTheJobsItSetsARunIdFor() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CaseJobsAutoConfiguration.class))
        .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
            new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("CCD_JOBS_RUN_MARK_CASES", "2026-11-27")))
        )
        .withBean(DataSource.class, () -> dataSource)
        .withBean("marks", CaseJob.class, () -> new Job(reference -> { }))
        .withBean("unset", CaseJob.class, () -> new Job(reference -> { }) {
          @Override
          public String name() {
            return "not-run";
          }
        })
        .run(context -> {
          long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
          while (done().size() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(50);
          }
          assertThat(done()).containsExactly(1L, 2L, 3L);
          assertThat(jdbc.queryForList("select job_name from job_utils.case_job_run", Map.of(),
              String.class)).containsExactly("mark-cases");
        });
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
    return new CaseJobRunner(new NamedParameterJdbcTemplate(dataSource), tx, Duration.ofMinutes(5), 2);
  }

  /** Records each case it is applied to, in the job's transaction, then does {@code also}. */
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
    } catch (java.sql.SQLException e) {
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

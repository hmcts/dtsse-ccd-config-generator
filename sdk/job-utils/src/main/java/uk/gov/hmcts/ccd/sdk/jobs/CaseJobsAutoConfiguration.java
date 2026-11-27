package uk.gov.hmcts.ccd.sdk.jobs;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gov.hmcts.ccd.sdk.SystemEventExecutor;

/**
 * Runs each {@link CaseJob} bean whose {@code ccd.jobs.run.<name>} is set, as system events,
 * checking every minute for a run another instance has dropped.
 */
@AutoConfiguration(afterName = {
    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
    "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
    "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"})
@ConditionalOnBean(DataSource.class)
public class CaseJobsAutoConfiguration {

  @Bean
  CaseJobs caseJobs(ObjectProvider<CaseJob> jobs, Environment environment, DataSource dataSource,
      ObjectProvider<SystemEventExecutor> systemEvents) {
    Map<CaseJob, String> runs = runs(jobs.orderedStream().toList(), environment);
    if (runs.isEmpty()) {
      return new CaseJobs(() -> null, runs, Duration.ofMinutes(1));
    }
    SystemEventExecutor executor = systemEvents.getIfAvailable(() -> {
      throw new IllegalStateException("Case jobs run as system events, so running "
          + runs.keySet().iterator().next().name() + " needs a system user:"
          + " set ccd.decentralised-runtime.system-user.id");
    });
    NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
    return new CaseJobs(() -> new CaseJobRunner(jdbc, executor, Duration.ofMinutes(5), 500), runs,
        Duration.ofMinutes(1));
  }

  private static Map<CaseJob, String> runs(List<CaseJob> jobs, Environment environment) {
    // A system event's name is at most 30 characters.
    Pattern names = Pattern.compile("(?=.{1,30}$)[a-z0-9]+(-[a-z0-9]+)*");
    var seen = new HashSet<String>();
    var runs = new LinkedHashMap<CaseJob, String>();
    for (CaseJob job : jobs) {
      if (!names.matcher(job.name()).matches() || !seen.add(job.name())) {
        throw new IllegalStateException("Case job name '" + job.name() + "' must be unique, and"
            + " up to 30 lower case letters, digits and hyphens");
      }
      if (job.caseTypes().isEmpty()) {
        throw new IllegalStateException("Case job " + job.name() + " names no case types");
      }
      String runId = environment.getProperty("ccd.jobs.run." + job.name());
      if (runId != null && !runId.isBlank()) {
        runs.put(job, runId);
      }
    }
    return runs;
  }

  /** Runs the configured case jobs on one background thread while the application is up. */
  public static final class CaseJobs implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(CaseJobs.class);

    private final Supplier<CaseJobRunner> runners;
    private final Map<CaseJob, String> runs;
    private final Duration pollDelay;
    private ScheduledExecutorService executor;
    private CaseJobRunner runner;

    CaseJobs(Supplier<CaseJobRunner> runners, Map<CaseJob, String> runs, Duration pollDelay) {
      this.runners = runners;
      this.runs = runs;
      this.pollDelay = pollDelay;
    }

    @Override
    public void start() {
      if (runs.isEmpty()) {
        return;
      }
      // A stopped runner stays stopped, so each start has a new one.
      CaseJobRunner started = runners.get();
      runner = started;
      executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "case-jobs");
        thread.setDaemon(true);
        return thread;
      });
      executor.scheduleWithFixedDelay(() -> runAll(started), 0, pollDelay.toMillis(),
          TimeUnit.MILLISECONDS);
    }

    private void runAll(CaseJobRunner runner) {
      runs.forEach((job, runId) -> {
        try {
          runner.run(job, runId);
        } catch (Throwable e) {
          // Anything let out of the scheduled task would cancel it, silently, for good.
          log.error("Could not run {} run {}; trying again in {}", job.name(), runId, pollDelay, e);
        }
      });
    }

    @Override
    public void stop() {
      if (executor == null) {
        return;
      }
      runner.stop();
      executor.shutdown();
      try {
        executor.awaitTermination(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      executor = null;
    }

    @Override
    public boolean isRunning() {
      return executor != null;
    }
  }
}

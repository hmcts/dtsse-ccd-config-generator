package uk.gov.hmcts.divorce.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import uk.gov.hmcts.divorce.jobs.NoteEveryCase;

/**
 * The application starts with CCD_JOBS_RUN_NOTE_EVERY_CASE set, as an environment's flux values set it, over cases
 * that already exist, and the job notes each case of its type once, in a system event of its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ContextConfiguration(initializers = CaseJobSpringBootTest.RunIdInEnvironment.class)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:postgresql:15-alpine:///e2e",
    "spring.datasource.driverClassName=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.jms.servicebus.enabled=false",
    "spring.autoconfigure.exclude=com.azure.spring.cloud.autoconfigure.implementation.jms.ServiceBusJmsAutoConfiguration",
    "spring.flyway.locations=classpath:db/migration,classpath:db/job-cases",
    "ccd.sdk.decentralised=true",
    "ccd.decentralised-runtime.system-user.id=00000000-0000-0000-0000-000000000042",
    "ccd.decentralised-runtime.system-user.username=e2e-system-user",
    "ccd.decentralised-runtime.system-user.first-name=E2E",
    "ccd.decentralised-runtime.system-user.last-name=System"
})
class CaseJobSpringBootTest {

  @Autowired
  private NamedParameterJdbcTemplate db;

  @Test
  void aJobWhoseRunIdTheEnvironmentSetsRunsOverTheCasesOfItsTypeOnce() {
    await().atMost(Duration.ofSeconds(30)).until(() -> finished() != null);

    assertThat(db.queryForList("select reference from case_notes where note = :note order by reference",
        Map.of("note", NoteEveryCase.NOTE), Long.class))
        .containsExactly(1700000000000001L, 1700000000000002L);
    assertThat(db.queryForList("select cd.reference from ccd.case_event ce join ccd.case_data cd"
        + " on cd.id = ce.case_data_id where ce.event_id = :event order by cd.reference",
        Map.of("event", "note-every-case"), Long.class))
        .containsExactly(1700000000000001L, 1700000000000002L);
    // The withdrawn case, which the job skips, has no note and no event.
    assertThat(finished()).containsEntry("applied", 2L).containsEntry("skipped", 1L);
  }

  private Map<String, Object> finished() {
    return db.queryForList("select applied, skipped from job_utils.case_job_run where job_name = 'note-every-case'"
        + " and run_id = 'e2e-1' and finished_at is not null", Map.of()).stream().findFirst().orElse(null);
  }

  /** Sets the run id as the operating system's environment would. */
  static class RunIdInEnvironment implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
      context.getEnvironment().getPropertySources().addFirst(new SystemEnvironmentPropertySource(
          "jobRunIds", Map.of("CCD_JOBS_RUN_NOTE_EVERY_CASE", "e2e-1")));
    }
  }
}

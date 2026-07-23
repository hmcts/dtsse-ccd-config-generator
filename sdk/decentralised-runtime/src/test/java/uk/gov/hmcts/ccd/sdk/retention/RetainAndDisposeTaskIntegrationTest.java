package uk.gov.hmcts.ccd.sdk.retention;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedDataConfiguration;
import uk.gov.hmcts.ccd.sdk.impl.PostgresAdvisoryLock;

@SpringBootTest(classes = RetainAndDisposeTaskIntegrationTest.TestConfig.class, properties = {
    "spring.datasource.url=jdbc:tc:postgresql:15-alpine:///ccd",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
})
class RetainAndDisposeTaskIntegrationTest {

  private static final String JURISDICTION = "TEST";

  @Autowired
  private DataSource dataSource;

  @Autowired
  private NamedParameterJdbcTemplate jdbc;

  @Autowired
  private PlatformTransactionManager transactionManager;

  private final RetainAndDisposeProperties properties = new RetainAndDisposeProperties();
  private final TestPolicy policy = new TestPolicy();
  private final CoreCaseDataRetainAndDisposeClient ccdClient =
      mock(CoreCaseDataRetainAndDisposeClient.class);

  private RetainAndDisposeTask task;
  private long nextReference;

  @BeforeEach
  void setUp() {
    jdbc.getJdbcTemplate().execute("truncate table ccd.case_data cascade");
    reset(ccdClient);
    policy.clear();
    properties.setMode(RetainAndDisposeProperties.Mode.LIVE);
    properties.setMaximumCandidatePercentage(5);
    properties.setMinimumCandidateCount(1);
    nextReference = 1_000_000_000_000_000L;

    task = new RetainAndDisposeTask(
        properties,
        policy,
        new RetainAndDisposeRepository(JdbcClient.create(dataSource)),
        ccdClient,
        new TransactionTemplate(transactionManager),
        new PostgresAdvisoryLock(dataSource)
    );
  }

  @Test
  void allowsCandidatePercentageExactlyAtConfiguredMaximum() {
    RetainAndDisposeCase candidate = insertPopulation("CaseTypeA", "Draft", 20, 1);

    task.run();

    verify(ccdClient).markForDisposal(candidate);
  }

  @Test
  void tripsWhenCandidatePercentageExceedsConfiguredMaximum() {
    insertPopulation("CaseTypeA", "Draft", 20, 2);
    insertResolvedPopulation("CaseTypeA", "Draft", 20);

    assertCircuitBreakerTrips();
  }

  @Test
  void bypassesCandidatePercentageBelowMinimumCandidateCount() {
    properties.setMinimumCandidateCount(2);
    RetainAndDisposeCase candidate = insertPopulation("CaseTypeA", "Draft", 1, 1);

    task.run();

    verify(ccdClient).markForDisposal(candidate);
  }

  @Test
  void appliesCandidatePercentageAtMinimumCandidateCount() {
    properties.setMinimumCandidateCount(2);
    insertPopulation("CaseTypeA", "Draft", 20, 2);

    assertCircuitBreakerTrips();
  }

  @Test
  void calculatesCandidatePercentageIndependentlyForEachCaseTypeAndState() {
    insertPopulation("CaseTypeA", "Draft", 20, 1);
    insertPopulation("CaseTypeA", "Submitted", 20, 2);
    insertPopulation("CaseTypeB", "Draft", 20, 1);

    assertCircuitBreakerTrips();
  }

  @Test
  void zeroMaximumPercentageRejectsAnyCandidate() {
    properties.setMaximumCandidatePercentage(0);
    insertPopulation("CaseTypeA", "Draft", 1, 1);

    assertCircuitBreakerTrips();
  }

  @Test
  void oneHundredMaximumPercentageDisablesCircuitBreaker() {
    properties.setMaximumCandidatePercentage(100);
    RetainAndDisposeCase candidate = insertPopulation("CaseTypeA", "Draft", 1, 1);

    task.run();

    verify(ccdClient).markForDisposal(candidate);
  }

  private void assertCircuitBreakerTrips() {
    assertThatThrownBy(task::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("configured maximum percentage");
    verifyNoInteractions(ccdClient);
  }

  private RetainAndDisposeCase insertPopulation(
      String caseTypeId,
      String state,
      int totalCount,
      int candidateCount
  ) {
    RetainAndDisposeCase firstCandidate = null;
    policy.caseTypes.add(caseTypeId);
    for (int index = 0; index < totalCount; index++) {
      long reference = insertCase(caseTypeId, state, false);
      if (index < candidateCount) {
        policy.candidates.add(reference);
        if (firstCandidate == null) {
          firstCandidate = new RetainAndDisposeCase(reference, JURISDICTION, caseTypeId, state);
        }
      }
    }
    return firstCandidate;
  }

  private void insertResolvedPopulation(String caseTypeId, String state, int count) {
    for (int index = 0; index < count; index++) {
      insertCase(caseTypeId, state, true);
    }
  }

  private long insertCase(String caseTypeId, String state, boolean resolved) {
    long reference = nextReference++;
    jdbc.getJdbcTemplate().update(
        """
        insert into ccd.case_data (
            id, reference, version, security_classification, jurisdiction, case_type_id, state, data, resolved_ttl
        ) values (?, ?, 1, 'PUBLIC', ?, ?, ?, '{}'::jsonb, case when ? then current_date else null end)
        """,
        reference,
        reference,
        JURISDICTION,
        caseTypeId,
        state,
        resolved
    );
    return reference;
  }

  private static final class TestPolicy implements RetainAndDisposePolicy {
    private final Set<String> caseTypes = new LinkedHashSet<>();
    private final Set<Long> candidates = new LinkedHashSet<>();

    @Override
    public Set<String> caseTypes() {
      return Set.copyOf(caseTypes);
    }

    @Override
    public Collection<Long> findCandidatesForDisposal() {
      return Set.copyOf(candidates);
    }

    private void clear() {
      caseTypes.clear();
      candidates.clear();
    }
  }

  @Configuration
  @Import(DecentralisedDataConfiguration.class)
  @ImportAutoConfiguration({
      DataSourceAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class,
      JdbcTemplateAutoConfiguration.class,
      TransactionAutoConfiguration.class,
      FlywayAutoConfiguration.class
  })
  static class TestConfig {
  }
}

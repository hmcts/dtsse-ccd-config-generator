package uk.gov.hmcts.ccd.sdk.retention;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedDataConfiguration;
import uk.gov.hmcts.ccd.sdk.impl.PostgresAdvisoryLock;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.idam.client.IdamClient;

@AutoConfiguration(after = DecentralisedDataConfiguration.class)
@ConditionalOnBean(RetainAndDisposePolicy.class)
public class RetainAndDisposeAutoConfiguration {

  @Bean
  CoreCaseDataRetainAndDisposeClient ccdRetainAndDisposeClient(
      CoreCaseDataApi coreCaseDataApi,
      AuthTokenGenerator authTokenGenerator,
      IdamClient idamClient,
      @Value("${ccd.decentralised-runtime.retain-and-dispose.system-user.username:}") String username,
      @Value("${ccd.decentralised-runtime.retain-and-dispose.system-user.password:}") String password
  ) {
    return new CoreCaseDataRetainAndDisposeClient(
        coreCaseDataApi,
        authTokenGenerator,
        idamClient,
        username,
        password
    );
  }

  @Bean(name = "retainAndDisposeTask")
  RetainAndDisposeTask retainAndDisposeTask(
      RetainAndDisposePolicy policy,
      NamedParameterJdbcTemplate jdbcTemplate,
      CoreCaseDataRetainAndDisposeClient ccdClient,
      DataSource dataSource,
      TransactionTemplate transactionTemplate
  ) {
    RetainAndDisposeRepository repository = new RetainAndDisposeRepository(jdbcTemplate);
    return new RetainAndDisposeTask(
        policy,
        repository,
        ccdClient,
        new RetainAndDisposeCaseReconciler(policy, repository, ccdClient, transactionTemplate),
        new PostgresAdvisoryLock(dataSource)
    );
  }
}

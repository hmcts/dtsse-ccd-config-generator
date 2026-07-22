package uk.gov.hmcts.ccd.sdk.retention;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;
import uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedDataConfiguration;
import uk.gov.hmcts.ccd.sdk.impl.PostgresAdvisoryLock;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.idam.client.IdamClient;

@AutoConfiguration(after = DecentralisedDataConfiguration.class)
@ConditionalOnSingleCandidate(RetainAndDisposePolicy.class)
@EnableConfigurationProperties(RetainAndDisposeProperties.class)
public class RetainAndDisposeAutoConfiguration {

  @Bean
  CoreCaseDataRetainAndDisposeClient ccdRetainAndDisposeClient(
      CoreCaseDataApi coreCaseDataApi,
      AuthTokenGenerator authTokenGenerator,
      IdamClient idamClient,
      RetainAndDisposeProperties properties
  ) {
    RetainAndDisposeProperties.SystemUser systemUser = properties.getSystemUser();
    return new CoreCaseDataRetainAndDisposeClient(
        coreCaseDataApi,
        authTokenGenerator,
        idamClient,
        systemUser.getUsername(),
        systemUser.getPassword()
    );
  }

  @Bean
  Runnable retainAndDisposeTask(
      RetainAndDisposePolicy policy,
      JdbcClient jdbcClient,
      CoreCaseDataRetainAndDisposeClient ccdClient,
      DataSource dataSource,
      TransactionOperations transaction
  ) {
    RetainAndDisposeRepository repository = new RetainAndDisposeRepository(jdbcClient);
    return new RetainAndDisposeTask(
        policy,
        repository,
        ccdClient,
        transaction,
        new PostgresAdvisoryLock(dataSource)
    );
  }
}

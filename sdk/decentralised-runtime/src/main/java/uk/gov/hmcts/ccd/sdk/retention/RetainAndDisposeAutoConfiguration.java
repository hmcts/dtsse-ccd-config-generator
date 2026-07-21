package uk.gov.hmcts.ccd.sdk.retention;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedDataConfiguration;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.idam.client.IdamClient;

@AutoConfiguration(after = DecentralisedDataConfiguration.class)
@EnableConfigurationProperties(RetainAndDisposeProperties.class)
@ConditionalOnBean(RetainAndDisposePolicy.class)
public class RetainAndDisposeAutoConfiguration {

  @Bean
  RetainAndDisposeRepository retainAndDisposeRepository(NamedParameterJdbcTemplate jdbcTemplate) {
    return new RetainAndDisposeRepository(jdbcTemplate);
  }

  @Bean
  @ConditionalOnMissingBean(CcdRetainAndDisposeClient.class)
  CcdRetainAndDisposeClient ccdRetainAndDisposeClient(
      CoreCaseDataApi coreCaseDataApi,
      AuthTokenGenerator authTokenGenerator,
      IdamClient idamClient,
      RetainAndDisposeProperties properties
  ) {
    return new CoreCaseDataRetainAndDisposeClient(coreCaseDataApi, authTokenGenerator, idamClient, properties);
  }

  @Bean(name = "retainAndDisposeTask")
  @ConditionalOnMissingBean(RetainAndDisposeTask.class)
  RetainAndDisposeTask retainAndDisposeTask(
      RetainAndDisposePolicy policy,
      RetainAndDisposeRepository repository,
      CcdRetainAndDisposeClient ccdClient,
      DataSource dataSource,
      PlatformTransactionManager transactionManager
  ) {
    return new RetainAndDisposeTask(policy, repository, ccdClient, dataSource, transactionManager);
  }
}

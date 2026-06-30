package uk.gov.hmcts.ccd.sdk.migration;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@AutoConfiguration
@EnableConfigurationProperties(CcdDataMigrationProperties.class)
public class CcdDataMigrationAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(CcdDataMigrationTask.class)
  @ConditionalOnProperty(prefix = "ccd.data-migration", name = "enabled", havingValue = "true")
  public CcdDataMigrationTask ccdDataMigrationTask(
      NamedParameterJdbcTemplate jdbcTemplate,
      PlatformTransactionManager transactionManager,
      CcdDataMigrationProperties properties,
      Environment environment
  ) {
    return new CcdDataMigrationTask(
        jdbcTemplate,
        transactionManager,
        properties.toOptions(),
        environment
    );
  }
}

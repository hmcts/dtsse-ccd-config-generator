package uk.gov.hmcts.ccd.sdk;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.*;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;

import javax.sql.DataSource;

/**
 * Based on https://github.com/spring-projects/spring-boot/blob/main/spring-boot-project/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/flyway/FlywayAutoConfiguration.java
 */
@AutoConfiguration (
  before = {FlywayAutoConfiguration.class}
)
@ConditionalOnClass(Flyway.class)
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", matchIfMissing = true)
public class DecentralisedDataConfiguration {


  @Autowired
  DecentralisedDataConfiguration(ResourceLoader resourceLoader,
                                 DataSource dataSource) {
    FluentConfiguration configuration = new FluentConfiguration(resourceLoader.getClassLoader());
    configuration.locations("classpath:dataruntime-db/migration");
    configuration.schemas("ccd");
    configuration.dataSource(dataSource);
    configuration.load().migrate();
  }

  /**
   * Delay spring boot's default flyway migration until after the decentralised data migration.
   */
  @Bean
  public FlywayMigrationInitializer flywayInitializer(Flyway flyway,
                                                      ObjectProvider<FlywayMigrationStrategy> migrationStrategy) {
    return new FlywayMigrationInitializer(flyway, migrationStrategy.getIfAvailable());
  }
}

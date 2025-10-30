package uk.gov.hmcts.ccd.sdk;

import java.util.Properties;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.core.io.ResourceLoader;
import uk.gov.hmcts.ccd.config.MessagingProperties;

@AutoConfiguration(
    before = {FlywayAutoConfiguration.class}
)
@ComponentScan(
    basePackageClasses = {MessagingProperties.class},
    // Fully qualify our beans to avoid name clashes with application beans
    nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class
)
@ConditionalOnClass(Flyway.class)
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", matchIfMissing = true)
public class DecentralisedDataConfiguration {

  /**
   * Enforce ordering so SDK migrations always run before the application migrations.
   */
  @Bean
  @ConditionalOnMissingBean(FlywayMigrationStrategy.class)
  public FlywayMigrationStrategy orderedFlywayMigrationStrategy(
      ResourceLoader resourceLoader,
      DataSource dataSource) {
    return (Flyway appFlyway) -> {
      Properties flywayProperties = new Properties();
      // We want to build indexes concurrently
      // https://documentation.red-gate.com/fd/flyway-postgresql-transactional-lock-setting-277579114
      flywayProperties.setProperty("flyway.postgresql.transactional.lock", "false");

      Flyway sdkFlyway = Flyway.configure(resourceLoader.getClassLoader())
          .configuration(flywayProperties)
          .dataSource(dataSource)
          .schemas("ccd")
          .locations("classpath:dataruntime-db/migration")
          .load();
      sdkFlyway.migrate();
      if (appFlyway != null) {
        appFlyway.migrate();
      }
    };
  }
}

package uk.gov.hmcts.ccd.sdk.config;

import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;

@AutoConfiguration(before = FlywayAutoConfiguration.class)
@ConditionalOnClass(Flyway.class)
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(SdkFlywayProperties.class)
public class DecentralisedFlywayAutoConfiguration {

  private static final String SDK_CALLBACK_LOCATION = "classpath:sdk-db/callback";

  @Bean
  public SdkFlywayMigration decentralisedRuntimeMigration() {
    return new SdkFlywayMigration(
        DecentralisedFlywayAutoConfiguration.class,
        "ccd",
        "classpath:dataruntime-db/migration");
  }

  /**
   * Run library migrations in dependency order, followed by the application's migrations.
   */
  @Bean
  @ConditionalOnMissingBean(FlywayMigrationStrategy.class)
  public FlywayMigrationStrategy orderedFlywayMigrationStrategy(
      ResourceLoader resourceLoader,
      DataSource dataSource,
      ObjectProvider<SdkFlywayMigration> migrations,
      SdkFlywayProperties properties) {
    return (Flyway appFlyway) -> {
      Properties flywayProperties = new Properties();
      // We want to build indexes concurrently
      // https://documentation.red-gate.com/fd/flyway-postgresql-transactional-lock-setting-277579114
      flywayProperties.setProperty("flyway.postgresql.transactional.lock", "false");

      for (SdkFlywayMigration migration
          : SdkFlywayMigrationOrder.sort(migrations.orderedStream().toList())) {
        Flyway.configure(resourceLoader.getClassLoader())
            .configuration(flywayProperties)
            .dataSource(dataSource)
            .defaultSchema(migration.schema())
            .schemas(migration.schema())
            .table(migration.historyTable())
            .placeholders(Map.of("sdkReaderRole", properties.getReaderRole()))
            .locations(Stream.concat(
                migration.locations().stream(),
                Stream.of(SDK_CALLBACK_LOCATION)
            ).toArray(String[]::new))
            .load()
            .migrate();
      }
      appFlyway.migrate();
    };
  }
}

package uk.gov.hmcts.ccd.sdk.bundling.job;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The module-owned Flyway migration against real PostgreSQL: with a {@code DataSource} and
 * Flyway on the classpath the auto-configuration applies {@code document-bundling-db/migration}
 * on startup under its own history table, so the outbox beans never meet a missing
 * {@code ccd_bundle_job} table; the application's own Flyway history is untouched, so the
 * module's version numbers cannot collide with the consumer's.
 */
@Testcontainers
class BundleJobFlywayMigrationTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Configuration
  static class DataSourceConfig {
    @Bean
    DataSource dataSource() {
      return new DriverManagerDataSource(
          POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
  }

  @Test
  void appliesTheOutboxSchemaUnderItsOwnHistoryTable() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BundleJobAutoConfiguration.class))
        .withUserConfiguration(DataSourceConfig.class)
        .run(context -> {
          assertThat(context).hasBean("bundleJobFlywayMigration");
          JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
          assertThat(jdbc.queryForObject(
              "select count(*) from information_schema.tables where table_name = 'ccd_bundle_job'",
              Integer.class)).isEqualTo(1);
          assertThat(jdbc.queryForObject(
              "select count(*) from information_schema.tables where table_name = '"
                  + BundleJobAutoConfiguration.BundleJobFlywayConfiguration.HISTORY_TABLE + "'",
              Integer.class)).isEqualTo(1);
          // Rows are usable straight away: the migration ran during context refresh.
          assertThat(jdbc.queryForObject("select count(*) from ccd_bundle_job", Integer.class))
              .isZero();
        });
  }
}

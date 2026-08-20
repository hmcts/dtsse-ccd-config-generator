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
 * The module-owned Flyway migration against the case every real adoption hits: a database whose
 * default schema already contains the application's own tables and its own
 * {@code flyway_schema_history}. The module instance baselines on migrate at version 0, so the
 * non-empty schema is accepted (no "found non-empty schema(s) … but no schema history table"
 * refusal) AND {@code V0001} genuinely applies — a default baseline version of 1 would silently
 * skip it and leave the {@code ccd_bundle_job} table missing, which the table assertion pins.
 *
 * <p>{@link BundleJobFlywayMigrationTest} covers the complementary fresh-empty-database case.
 */
@Testcontainers
class BundleJobFlywayNonEmptySchemaTest {

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
  void migratesWhenTheConsumersSchemaAlreadyHoldsApplicationTables() {
    // A consuming service's schema is never empty: simulate the app's own migrated tables.
    DataSource ds = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(ds);
    jdbc.execute("create table if not exists consumer_app_table (id int primary key)");
    jdbc.execute("create table if not exists flyway_schema_history "
        + "(installed_rank int primary key, version varchar(50))");

    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BundleJobAutoConfiguration.class))
        .withUserConfiguration(DataSourceConfig.class)
        .run(context -> {
          // The module applies its schema on startup for such consumers: the pre-populated
          // schema is baselined (at version 0) rather than refused, and V0001 still runs —
          // the outbox table existing afterwards proves it was applied, not baselined away.
          assertThat(context).hasNotFailed();
          assertThat(jdbc.queryForObject(
              "select count(*) from information_schema.tables where table_name = 'ccd_bundle_job'",
              Integer.class)).isEqualTo(1);
        });
  }
}

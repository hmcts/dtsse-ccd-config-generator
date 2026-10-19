package uk.gov.hmcts.ccd.sdk.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * An application that connects at runtime as a role without DDL, and migrates as its owner through
 * {@code spring.flyway.user}. Library migrations have to run as the owner too, or the {@code ccd} schema cannot be
 * created and startup fails.
 */
@SpringBootTest(
    classes = SdkFlywayMigrationCredentialsIntegrationTest.Config.class,
    properties = {
        "spring.datasource.url=jdbc:tc:postgresql:15-alpine:///ccd"
            + "?TC_INITSCRIPT=sdk-flyway/runtime-role.sql&TC_DAEMON=true",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=sdk_runtime",
        "spring.datasource.password=not-a-secret",
        "spring.flyway.user=test",
        "spring.flyway.password=test",
        "spring.flyway.locations=classpath:sdk-flyway/application"
    })
class SdkFlywayMigrationCredentialsIntegrationTest {

  @Autowired
  private JdbcTemplate jdbc;

  @Test
  void runsLibraryMigrationsWithTheFlywayUserRatherThanTheApplicationDataSource() {
    assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo("sdk_runtime");
    assertThat(jdbc.queryForObject(
        "select tableowner from pg_tables where schemaname = 'ccd' and tablename = 'case_data'",
        String.class)).isEqualTo("test");
    assertThat(jdbc.queryForObject(
        "select tableowner from pg_tables where schemaname = 'public' and tablename = 'sdk_migration_order_check'",
        String.class)).isEqualTo("test");
  }

  @Configuration
  @ImportAutoConfiguration({
      DataSourceAutoConfiguration.class,
      JdbcTemplateAutoConfiguration.class,
      DecentralisedFlywayAutoConfiguration.class,
      FlywayAutoConfiguration.class
  })
  static class Config {
  }
}

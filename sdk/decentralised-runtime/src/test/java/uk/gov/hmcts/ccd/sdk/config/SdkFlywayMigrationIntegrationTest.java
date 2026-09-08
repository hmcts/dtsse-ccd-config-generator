package uk.gov.hmcts.ccd.sdk.config;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = SdkFlywayMigrationIntegrationTest.Config.class,
    properties = {
        "spring.datasource.url=jdbc:tc:postgresql:15-alpine:///ccd",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.flyway.enabled=false"
    })
class SdkFlywayMigrationIntegrationTest {

  private static final String READER_ROLE = "DTS JIT Access ccd DB Reader SC";

  @Autowired
  private JdbcTemplate jdbc;
  @Autowired
  private DataSource dataSource;

  @Test
  void migratesLibrariesBeforeTheApplicationAndGrantsReadAccess() {
    jdbc.execute("create role \"" + READER_ROLE + "\"");

    var configuration = new DecentralisedFlywayAutoConfiguration();
    var migrations = new StaticListableBeanFactory();
    migrations.addBean("runtime", configuration.decentralisedRuntimeMigration());
    migrations.addBean("testLibrary", testLibraryMigration());
    var appFlyway = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:sdk-flyway/application")
        .load();

    configuration.orderedFlywayMigrationStrategy(
        new DefaultResourceLoader(),
        dataSource,
        migrations.getBeanProvider(SdkFlywayMigration.class)
    ).migrate(appFlyway);

    assertThat(jdbc.queryForObject(
        "select runtime_migrated and library_migrated from sdk_migration_order_check",
        Boolean.class)).isTrue();
    assertThat(jdbc.queryForObject(
        "select has_schema_privilege(?, ?, 'USAGE')",
        Boolean.class,
        READER_ROLE,
        "test_library")).isTrue();
    assertThat(jdbc.queryForObject(
        "select has_table_privilege(?, ?, 'SELECT')",
        Boolean.class,
        READER_ROLE,
        "test_library.existing_table")).isTrue();

    jdbc.execute("create table test_library.future_table (id bigint primary key)");

    assertThat(jdbc.queryForObject(
        "select has_table_privilege(?, ?, 'SELECT')",
        Boolean.class,
        READER_ROLE,
        "test_library.future_table")).isTrue();
  }

  private SdkFlywayMigration testLibraryMigration() {
    return new SdkFlywayMigration(
        TestLibrary.class,
        "test_library",
        "classpath:sdk-flyway/test-library",
        DecentralisedFlywayAutoConfiguration.class);
  }

  @Configuration
  @ImportAutoConfiguration({
      DataSourceAutoConfiguration.class,
      JdbcTemplateAutoConfiguration.class
  })
  static class Config {
  }

  private static final class TestLibrary {
  }
}

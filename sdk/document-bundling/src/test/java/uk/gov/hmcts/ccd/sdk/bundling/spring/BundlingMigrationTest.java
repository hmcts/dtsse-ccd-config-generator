package uk.gov.hmcts.ccd.sdk.bundling.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;

@Testcontainers
class BundlingMigrationTest {
  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void migratingLeavesTheBundlingSchemaWithoutTables() {
    DataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
        POSTGRES.getUsername(), POSTGRES.getPassword());

    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DecentralisedFlywayAutoConfiguration.class,
            BundlingFlywayAutoConfiguration.class, FlywayAutoConfiguration.class))
        .withPropertyValues("spring.flyway.locations=classpath:consumer-db/migration")
        .withBean(DataSource.class, () -> dataSource)
        .run(context -> assertThat(context).hasNotFailed());

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    List<String> tables = jdbc.queryForList("""
        select table_name from information_schema.tables
        where table_schema = 'bundling' and table_name not like 'flyway%'
        """, String.class);
    assertThat(tables).isEmpty();
    assertThat(jdbc.queryForObject(
        "select max(version) from bundling.flyway_schema_history", String.class))
        .isEqualTo("0005");
  }
}

package uk.gov.hmcts.ccd.sdk.doctoolkit.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;
import uk.gov.hmcts.ccd.sdk.doctoolkit.spring.DocToolkitFlywayAutoConfiguration;

/**
 * A fresh Postgres database migrated as a service's would be: the SDK's library migrations, then
 * the application's own. Uses Testcontainers, or the server at {@code DOC_TOOLKIT_TEST_POSTGRES}
 * (a JDBC URL with credentials) when set, for machines without Docker.
 */
public final class OutboxDatabase {
  private static DataSource dataSource;

  private OutboxDatabase() {
  }

  /** The migrated database, created on first use. */
  public static synchronized DataSource dataSource() {
    if (dataSource == null) {
      dataSource = create();
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(DecentralisedFlywayAutoConfiguration.class,
              DocToolkitFlywayAutoConfiguration.class, FlywayAutoConfiguration.class))
          .withPropertyValues("spring.flyway.locations=classpath:consumer-db/migration")
          .withBean(DataSource.class, () -> dataSource)
          .run(context -> assertThat(context).hasNotFailed());
    }
    return dataSource;
  }

  private static DataSource create() {
    String server = System.getenv("DOC_TOOLKIT_TEST_POSTGRES");
    if (server == null || server.isBlank()) {
      PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
      postgres.start();
      return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
          postgres.getPassword());
    }
    String database = "doc_toolkit_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection admin = DriverManager.getConnection(server)) {
      admin.createStatement().execute("create database " + database);
    } catch (SQLException e) {
      throw new IllegalStateException("Could not create a test database on " + server, e);
    }
    int query = server.indexOf('?');
    String base = query < 0 ? server : server.substring(0, query);
    String url = base.substring(0, base.lastIndexOf('/') + 1) + database
        + (query < 0 ? "" : server.substring(query));
    return new DriverManagerDataSource(url);
  }
}

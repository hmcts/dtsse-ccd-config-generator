package uk.gov.hmcts.ccd.sdk;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertTrue;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.commons.io.FileUtils;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs {@code migratePostgresDatabase} as the CNP pipeline's {@code enableDbMigration} does, against an application
 * configured as if deployed: a runtime login, Flyway off, and an SDK feature on that would not start here.
 */
public class MigrateDatabaseFunctionalTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15-alpine")
      .withDatabaseName("service")
      .withUsername("owner")
      .withPassword("owner-password");

  @Rule
  public final TemporaryFolder testProjectDir = new TemporaryFolder();

  @BeforeClass
  public static void startDatabase() {
    POSTGRES.start();
  }

  @AfterClass
  public static void stopDatabase() {
    POSTGRES.stop();
  }

  @Before
  public void setup() throws IOException {
    FileUtils.copyDirectory(new File("test-projects/migrate-database"), testProjectDir.getRoot());
  }

  @Test
  public void migratesTheLibrariesAndThenTheApplicationWithThePipelineCredentials() throws SQLException {
    BuildResult result = gradle(
        "migratePostgresDatabase",
        "-Pdburl=" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/service?sslmode=disable",
        "-Pflyway.user=owner",
        "-Pflyway.password=owner-password");

    assertEquals(TaskOutcome.SUCCESS, result.task(":migratePostgresDatabase").getOutcome());
    try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "owner", "owner-password")) {
      assertTrue(exists(connection, "ccd.flyway_schema_history"));
      assertTrue(exists(connection, "ccd_data_migration.flyway_schema_history"));
      // The application's migration reads ccd.case_data, so it ran after the SDK's.
      assertTrue(exists(connection, "public.case_references"));
    }
  }

  @Test
  public void leavesAnApplicationsOwnTaskInPlace() {
    BuildResult result = gradle("migratePostgresDatabase", "-PownTask");

    assertTrue(result.getOutput().contains("The application migrated its own database"));
  }

  private BuildResult gradle(String... arguments) {
    return GradleRunner.create()
        .withPluginClasspath()
        .withProjectDir(testProjectDir.getRoot())
        .withArguments(arguments)
        .withGradleVersion("8.14.5")
        .forwardOutput()
        .build();
  }

  private static boolean exists(Connection connection, String relation) throws SQLException {
    try (ResultSet rows = connection.createStatement()
        .executeQuery("select to_regclass('" + relation + "') is not null")) {
      rows.next();
      return rows.getBoolean(1);
    }
  }
}

package uk.gov.hmcts.ccd.sdk.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = CcdDataMigrationTaskIntegrationTest.TestConfig.class,
    properties = {
        "spring.datasource.url=jdbc:tc:postgresql:15-alpine:///ccd",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
    }
)
class CcdDataMigrationFdwCleanupExampleIntegrationTest {
  private static final String CLEANUP_EXAMPLE =
      "ccd-data-migration-db/examples/VXXXX__remove_ccd_migration_fdw.sql";

  @Autowired
  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    jdbc.execute("drop schema if exists fdw_stage cascade");
    jdbc.execute("drop server if exists src_ccd_server cascade");
    jdbc.execute("delete from ccd_data_migration.ccd_data_migration_progress");
    jdbc.execute("create extension if not exists postgres_fdw");
    jdbc.execute("create server src_ccd_server foreign data wrapper postgres_fdw");
    jdbc.execute("create user mapping for current_user server src_ccd_server");
    jdbc.execute("create schema fdw_stage");
    createForeignTable("case_data");
    createForeignTable("case_event");
    createForeignTable("case_event_significant_items");
  }

  @Test
  void removesMigrationFdwObjectsAndCanBeRunAgain() throws IOException {
    String sql = cleanupSql();

    jdbc.execute(sql);
    jdbc.execute(sql);

    assertThat(count("select count(*) from pg_foreign_server where srvname = 'src_ccd_server'"))
        .isZero();
    assertThat(count("select count(*) from pg_namespace where nspname = 'fdw_stage'"))
        .isZero();
    assertThat(count("select count(*) from pg_extension where extname = 'postgres_fdw'"))
        .isOne();
  }

  @Test
  void refusesCleanupWhileMigrationProgressIsIncomplete() throws IOException {
    jdbc.update("""
        insert into ccd_data_migration.ccd_data_migration_progress (
          task_name, config_hash, status
        ) values ('ET', repeat('a', 64), 'PRELOAD')
        """);

    assertThatThrownBy(() -> jdbc.execute(cleanupSql()))
        .hasMessageContaining("CCD data migration has incomplete progress rows")
        .hasMessageContaining("ET=PRELOAD");

    assertThat(count("select count(*) from pg_foreign_server where srvname = 'src_ccd_server'"))
        .isOne();
    assertThat(count("select count(*) from pg_namespace where nspname = 'fdw_stage'"))
        .isOne();
  }

  private void createForeignTable(String tableName) {
    jdbc.execute("create foreign table fdw_stage." + tableName
        + " (id bigint) server src_ccd_server options (schema_name 'public', table_name '"
        + tableName + "')");
  }

  private int count(String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }

  private String cleanupSql() throws IOException {
    return new ClassPathResource(CLEANUP_EXAMPLE).getContentAsString(StandardCharsets.UTF_8);
  }
}

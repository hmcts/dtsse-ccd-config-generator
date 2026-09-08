package uk.gov.hmcts.ccd.sdk.config;

import java.util.List;
import java.util.Set;

/**
 * Describes a library-owned set of Flyway migrations.
 *
 * @param library auto-configuration class identifying the owning library
 * @param dependsOn library migrations that must complete first
 * @param schema database schema managed by the migrations
 * @param historyTable library-specific Flyway schema history table
 * @param locations classpath locations containing the migrations
 */
public record SdkFlywayMigration(
    Class<?> library,
    Set<Class<?>> dependsOn,
    String schema,
    String historyTable,
    List<String> locations) {

  public SdkFlywayMigration(
      Class<?> library,
      String schema,
      String location,
      Class<?>... dependsOn) {
    this(library, Set.of(dependsOn), schema, "flyway_schema_history", List.of(location));
  }

  public SdkFlywayMigration {
    if (library == null) {
      throw new IllegalArgumentException("SDK migration library must not be null");
    }
    if (schema == null || schema.isBlank()) {
      throw new IllegalArgumentException("SDK migration schema must not be blank");
    }
    if (historyTable == null || historyTable.isBlank()) {
      throw new IllegalArgumentException("SDK migration history table must not be blank");
    }
    dependsOn = Set.copyOf(dependsOn);
    locations = List.copyOf(locations);
    if (locations.isEmpty()) {
      throw new IllegalArgumentException("SDK migration locations must not be empty");
    }
  }
}

package uk.gov.hmcts.ccd.sdk.config;

import java.util.Set;
import lombok.NonNull;

/**
 * Describes a library-owned set of Flyway migrations.
 *
 * @param library auto-configuration class identifying the owning library
 * @param dependsOn library migrations that must complete first
 * @param schema database schema managed by the migrations
 * @param location classpath location containing the migrations
 */
public record SdkFlywayMigration(
    @NonNull Class<?> library,
    @NonNull Set<Class<?>> dependsOn,
    @NonNull String schema,
    @NonNull String location) {

  public SdkFlywayMigration(
      Class<?> library,
      String schema,
      String location,
      Class<?>... dependsOn) {
    this(library, Set.of(dependsOn), schema, location);
  }

  public SdkFlywayMigration {
    if (schema.isBlank()) {
      throw new IllegalArgumentException("SDK migration schema must not be blank");
    }
    dependsOn = Set.copyOf(dependsOn);
    if (location.isBlank()) {
      throw new IllegalArgumentException("SDK migration location must not be blank");
    }
  }
}

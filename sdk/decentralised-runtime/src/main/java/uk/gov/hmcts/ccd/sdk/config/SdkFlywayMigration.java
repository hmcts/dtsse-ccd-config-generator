package uk.gov.hmcts.ccd.sdk.config;

import java.util.List;
import java.util.Set;
import lombok.NonNull;

/**
 * Describes a library-owned set of Flyway migrations.
 *
 * @param library auto-configuration class identifying the owning library
 * @param dependsOn library migrations that must complete first
 * @param schema database schema managed by the migrations
 * @param locations classpath locations containing the migrations
 */
public record SdkFlywayMigration(
    @NonNull Class<?> library,
    @NonNull Set<Class<?>> dependsOn,
    @NonNull String schema,
    @NonNull List<String> locations) {

  public SdkFlywayMigration(
      Class<?> library,
      String schema,
      String location,
      Class<?>... dependsOn) {
    this(library, Set.of(dependsOn), schema, List.of(location));
  }

  public SdkFlywayMigration {
    if (schema.isBlank()) {
      throw new IllegalArgumentException("SDK migration schema must not be blank");
    }
    dependsOn = Set.copyOf(dependsOn);
    locations = List.copyOf(locations);
    if (locations.isEmpty()) {
      throw new IllegalArgumentException("SDK migration locations must not be empty");
    }
  }
}

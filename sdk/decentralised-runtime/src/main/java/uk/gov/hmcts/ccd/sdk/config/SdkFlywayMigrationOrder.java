package uk.gov.hmcts.ccd.sdk.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SdkFlywayMigrationOrder {

  private SdkFlywayMigrationOrder() {
  }

  static List<SdkFlywayMigration> sort(Collection<SdkFlywayMigration> migrations) {
    Map<Class<?>, SdkFlywayMigration> byLibrary = new HashMap<>();
    for (SdkFlywayMigration migration : migrations) {
      if (byLibrary.put(migration.library(), migration) != null) {
        throw new IllegalStateException(
            "Duplicate SDK migration library: " + migration.library().getName());
      }
    }

    List<SdkFlywayMigration> ordered = new ArrayList<>(migrations.size());
    Set<Class<?>> visiting = new HashSet<>();
    Set<Class<?>> visited = new HashSet<>();
    migrations.stream()
        .sorted(Comparator.comparing(migration -> migration.library().getName()))
        .forEach(migration -> visit(migration, byLibrary, visiting, visited, ordered));
    return List.copyOf(ordered);
  }

  private static void visit(
      SdkFlywayMigration migration,
      Map<Class<?>, SdkFlywayMigration> migrations,
      Set<Class<?>> visiting,
      Set<Class<?>> visited,
      List<SdkFlywayMigration> ordered) {
    if (visited.contains(migration.library())) {
      return;
    }
    if (!visiting.add(migration.library())) {
      throw new IllegalStateException(
          "Cyclic SDK migration dependency involving " + migration.library().getName());
    }

    migration.dependsOn().stream()
        .sorted(Comparator.comparing(Class::getName))
        .map(dependency -> requiredDependency(migration, dependency, migrations))
        .forEach(dependency -> visit(dependency, migrations, visiting, visited, ordered));

    visiting.remove(migration.library());
    visited.add(migration.library());
    ordered.add(migration);
  }

  private static SdkFlywayMigration requiredDependency(
      SdkFlywayMigration migration,
      Class<?> dependency,
      Map<Class<?>, SdkFlywayMigration> migrations) {
    SdkFlywayMigration required = migrations.get(dependency);
    if (required == null) {
      throw new IllegalStateException(
          "SDK migration " + migration.library().getName()
              + " depends on missing migration " + dependency.getName());
    }
    return required;
  }
}

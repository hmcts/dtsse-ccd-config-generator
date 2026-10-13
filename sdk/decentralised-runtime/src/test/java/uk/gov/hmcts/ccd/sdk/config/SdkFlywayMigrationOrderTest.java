package uk.gov.hmcts.ccd.sdk.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class SdkFlywayMigrationOrderTest {

  @Test
  void ordersAnArbitraryDependencyGraphDeterministically() {
    var root = migration(Runtime.class);
    var alpha = migration(Alpha.class, Runtime.class);
    var beta = migration(Beta.class, Runtime.class);
    var feature = migration(Feature.class, Alpha.class, Beta.class);

    assertThat(SdkFlywayMigrationOrder.sort(List.of(feature, beta, root, alpha)))
        .extracting(SdkFlywayMigration::library)
        .containsExactly(Runtime.class, Alpha.class, Beta.class, Feature.class);
  }

  @Test
  void rejectsMissingDependencies() {
    assertThatThrownBy(() -> SdkFlywayMigrationOrder.sort(
        List.of(migration(Feature.class, Missing.class))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("SDK migration " + Feature.class.getName()
            + " depends on missing migration " + Missing.class.getName());
  }

  @Test
  void rejectsDuplicateLibraries() {
    assertThatThrownBy(() -> SdkFlywayMigrationOrder.sort(List.of(
        migration(Runtime.class),
        migration(Runtime.class))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Duplicate SDK migration library: " + Runtime.class.getName());
  }

  @Test
  void rejectsDependencyCycles() {
    assertThatThrownBy(() -> SdkFlywayMigrationOrder.sort(List.of(
        migration(Alpha.class, Beta.class),
        migration(Beta.class, Alpha.class))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cyclic SDK migration dependency")
        .hasMessageContaining(Alpha.class.getName());
  }

  @Test
  void rejectsNullDependenciesClearly() {
    assertThatThrownBy(() -> new SdkFlywayMigration(
        Runtime.class,
        null,
        "ccd",
        "classpath:runtime-db/migration"))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("dependsOn is marked non-null but is null");
  }

  private SdkFlywayMigration migration(Class<?> library, Class<?>... dependencies) {
    return new SdkFlywayMigration(
        library,
        "ccd",
        "classpath:" + library.getSimpleName().toLowerCase() + "-db/migration",
        dependencies);
  }

  private static final class Runtime {
  }

  private static final class Alpha {
  }

  private static final class Beta {
  }

  private static final class Feature {
  }

  private static final class Missing {
  }
}

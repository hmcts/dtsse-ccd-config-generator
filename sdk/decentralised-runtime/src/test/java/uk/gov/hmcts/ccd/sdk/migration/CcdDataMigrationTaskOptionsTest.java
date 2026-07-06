package uk.gov.hmcts.ccd.sdk.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CcdDataMigrationTaskOptionsTest {

  @Test
  void buildsWithDefaults() {
    var options = CcdDataMigrationTaskOptions.builder(List.of("TestCase")).build();

    assertThat(options.taskName()).isEqualTo("ccd-data-migration");
    assertThat(options.mode()).isEqualTo(CcdDataMigrationMode.PRELOAD_EVENTS);
    assertThat(options.eventBatchSize()).isEqualTo(10_000);
    assertThat(options.caseRevisionOffset()).isEqualTo(1_000_000_000L);
    assertThat(options.maxBatchesPerRun()).isEqualTo(Integer.MAX_VALUE);
    assertThat(options.maxRunTime()).isNull();
    assertThat(options.statementTimeout()).isEqualTo(Duration.ofMinutes(10));
  }

  @Test
  void migrationConfigHashIgnoresRuntimeLimitsAndCaseTypeOrder() {
    var first = CcdDataMigrationTaskOptions.builder(List.of("CaseB", "CaseA"))
        .mode(CcdDataMigrationMode.CUTOVER)
        .eventBatchSize(1)
        .maxBatchesPerRun(1)
        .maxRunTime(Duration.ofMinutes(10))
        .statementTimeout(Duration.ofSeconds(30))
        .build();

    var second = CcdDataMigrationTaskOptions.builder(List.of("CaseA", "CaseB"))
        .mode(CcdDataMigrationMode.VALIDATE_ONLY)
        .eventBatchSize(500)
        .maxBatchesPerRun(500)
        .maxRunTime(Duration.ofHours(4))
        .statementTimeout(Duration.ofMinutes(15))
        .build();

    assertThat(second.migrationConfigHash()).isEqualTo(first.migrationConfigHash());
    assertThat(second.canonicalCaseTypeIds()).isEqualTo("CaseA,CaseB");
  }

  @Test
  void migrationConfigHashChangesForMigrationIdentityChanges() {
    var original = CcdDataMigrationTaskOptions.builder(List.of("TestCase")).build();

    assertThat(CcdDataMigrationTaskOptions.builder(List.of("OtherCase")).build().migrationConfigHash())
        .isNotEqualTo(original.migrationConfigHash());
    assertThat(CcdDataMigrationTaskOptions.builder(List.of("TestCase"))
        .caseRevisionOffset(2_000_000_000L)
        .build()
        .migrationConfigHash())
        .isNotEqualTo(original.migrationConfigHash());
  }

  @Test
  void rejectsEmptyCaseTypeIds() {
    assertThatThrownBy(() -> CcdDataMigrationTaskOptions.builder(List.of()).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("caseTypeIds");
  }

  @Test
  void rejectsNonPositiveMaxRunTime() {
    assertThatThrownBy(() -> CcdDataMigrationTaskOptions.builder(List.of("TestCase"))
        .maxRunTime(Duration.ZERO)
        .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxRunTime");
  }

  @Test
  void rejectsNonPositiveStatementTimeout() {
    assertThatThrownBy(() -> CcdDataMigrationTaskOptions.builder(List.of("TestCase"))
        .statementTimeout(Duration.ZERO)
        .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("statementTimeout");
  }

}

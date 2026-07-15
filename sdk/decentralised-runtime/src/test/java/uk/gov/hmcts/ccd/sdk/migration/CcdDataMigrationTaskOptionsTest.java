package uk.gov.hmcts.ccd.sdk.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CcdDataMigrationTaskOptionsTest {

  @Test
  void buildsWithDefaults() {
    var options = builder(List.of("TestCase")).build();

    assertThat(options.taskName()).isEqualTo("ccd-data-migration");
    assertThat(options.mode()).isEqualTo(CcdDataMigrationMode.PRELOAD_EVENTS);
    assertThat(options.sourceJurisdiction()).isEqualTo("TEST");
    assertThat(options.eventIdWindowSize()).isEqualTo(1_000_000);
    assertThat(options.significantItemIdWindowSize()).isEqualTo(100_000);
    assertThat(options.caseRevisionOffset()).isEqualTo(1_000_000_000L);
    assertThat(options.maxBatchesPerRun()).isEqualTo(Integer.MAX_VALUE);
    assertThat(options.maxRunTime()).isNull();
    assertThat(options.statementTimeout()).isEqualTo(Duration.ofMinutes(10));
    assertThat(options.sourceEventSafetyWindow()).isEqualTo(Duration.ofMinutes(2));
    assertThat(options.fdwAdditionalSelectGrantee()).isNull();
  }

  @Test
  void migrationConfigHashIgnoresRuntimeLimitsAndCaseTypeOrder() {
    var first = builder(List.of("CaseB", "CaseA"))
        .mode(CcdDataMigrationMode.CUTOVER)
        .eventIdWindowSize(1)
        .maxBatchesPerRun(1)
        .maxRunTime(Duration.ofMinutes(10))
        .statementTimeout(Duration.ofSeconds(30))
        .sourceEventSafetyWindow(Duration.ZERO)
        .build();

    var second = builder(List.of("CaseA", "CaseB"))
        .mode(CcdDataMigrationMode.VALIDATE_ONLY)
        .eventIdWindowSize(500)
        .maxBatchesPerRun(500)
        .maxRunTime(Duration.ofHours(4))
        .statementTimeout(Duration.ofMinutes(15))
        .sourceEventSafetyWindow(Duration.ofMinutes(5))
        .build();

    assertThat(second.migrationConfigHash()).isEqualTo(first.migrationConfigHash());
    assertThat(second.canonicalCaseTypeIds()).isEqualTo("CaseA,CaseB");
  }

  @Test
  void trimsOptionalFdwAdditionalSelectGrantee() {
    var options = builder(List.of("TestCase"))
        .fdwAdditionalSelectGrantee("  DTS JIT Access et DB Reader SC  ")
        .build();

    assertThat(options.fdwAdditionalSelectGrantee()).isEqualTo("DTS JIT Access et DB Reader SC");
  }

  @Test
  void treatsBlankFdwAdditionalSelectGranteeAsUnset() {
    var options = builder(List.of("TestCase"))
        .fdwAdditionalSelectGrantee(" ")
        .build();

    assertThat(options.fdwAdditionalSelectGrantee()).isNull();
  }

  @Test
  void migrationConfigHashChangesForMigrationIdentityChanges() {
    var original = builder(List.of("TestCase")).build();

    assertThat(builder(List.of("OtherCase")).build().migrationConfigHash())
        .isNotEqualTo(original.migrationConfigHash());
    assertThat(builder(List.of("TestCase"))
        .caseRevisionOffset(2_000_000_000L)
        .build()
        .migrationConfigHash())
        .isNotEqualTo(original.migrationConfigHash());
    assertThat(builder(List.of("TestCase"))
        .sourceJurisdiction("OTHER")
        .build()
        .migrationConfigHash())
        .isNotEqualTo(original.migrationConfigHash());
  }

  @Test
  void rejectsEmptyCaseTypeIds() {
    assertThatThrownBy(() -> builder(List.of()).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("caseTypeIds");
  }

  @Test
  void rejectsNonPositiveMaxRunTime() {
    assertThatThrownBy(() -> builder(List.of("TestCase"))
        .maxRunTime(Duration.ZERO)
        .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxRunTime");
  }

  @Test
  void rejectsNonPositiveEventIdWindowSize() {
    assertThatThrownBy(() -> builder(List.of("TestCase"))
        .eventIdWindowSize(0)
        .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("eventIdWindowSize");
  }

  @Test
  void rejectsNonPositiveSignificantItemIdWindowSize() {
    assertThatThrownBy(() -> builder(List.of("TestCase"))
        .significantItemIdWindowSize(0)
        .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("significantItemIdWindowSize");
  }

  @Test
  void rejectsNonPositiveStatementTimeout() {
    assertThatThrownBy(() -> builder(List.of("TestCase"))
        .statementTimeout(Duration.ZERO)
        .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("statementTimeout");
  }

  @Test
  void rejectsNegativeSourceEventSafetyWindow() {
    assertThatThrownBy(() -> builder(List.of("TestCase"))
        .sourceEventSafetyWindow(Duration.ofSeconds(-1))
        .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sourceEventSafetyWindow");
  }

  @Test
  void rejectsBlankSourceJurisdiction() {
    assertThatThrownBy(() -> CcdDataMigrationTaskOptions.builder(List.of("TestCase"))
        .sourceJurisdiction(" ")
        .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sourceJurisdiction");
  }

  private CcdDataMigrationTaskOptions.Builder builder(List<String> caseTypeIds) {
    return CcdDataMigrationTaskOptions.builder(caseTypeIds)
        .sourceJurisdiction("TEST");
  }

}

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
    assertThat(options.caseRevisionOffset()).isEqualTo(1_000_000_000L);
    assertThat(options.maxBatchesPerRun()).isEqualTo(Integer.MAX_VALUE);
    assertThat(options.maxRunTime()).isNull();
    assertThat(options.statementTimeout()).isEqualTo(Duration.ofMinutes(10));
  }

  @Test
  void migrationConfigHashIgnoresRuntimeLimitsAndCaseTypeOrder() {
    var first = builder(List.of("CaseB", "CaseA"))
        .mode(CcdDataMigrationMode.CUTOVER)
        .eventIdWindowSize(1)
        .maxBatchesPerRun(1)
        .maxRunTime(Duration.ofMinutes(10))
        .statementTimeout(Duration.ofSeconds(30))
        .build();

    var second = builder(List.of("CaseA", "CaseB"))
        .mode(CcdDataMigrationMode.VALIDATE_ONLY)
        .eventIdWindowSize(500)
        .maxBatchesPerRun(500)
        .maxRunTime(Duration.ofHours(4))
        .statementTimeout(Duration.ofMinutes(15))
        .build();

    assertThat(second.migrationConfigHash()).isEqualTo(first.migrationConfigHash());
    assertThat(second.canonicalCaseTypeIds()).isEqualTo("CaseA,CaseB");
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
  void rejectsNonPositiveStatementTimeout() {
    assertThatThrownBy(() -> builder(List.of("TestCase"))
        .statementTimeout(Duration.ZERO)
        .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("statementTimeout");
  }

  @Test
  @SuppressWarnings("deprecation")
  void supportsDeprecatedEventBatchSizeAlias() {
    assertThat(builder(List.of("TestCase"))
        .eventBatchSize(123)
        .build()
        .eventIdWindowSize()).isEqualTo(123);
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

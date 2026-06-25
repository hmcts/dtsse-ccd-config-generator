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
    assertThat(options.targetSchema()).isEqualTo("ccd");
    assertThat(options.fdwSchema()).isEqualTo("fdw_stage");
    assertThat(options.batchSize()).isEqualTo(100);
    assertThat(options.caseRevisionOffset()).isEqualTo(1_000_000_000L);
    assertThat(options.maxBatchesPerRun()).isEqualTo(Integer.MAX_VALUE);
    assertThat(options.maxRunTime()).isNull();
    assertThat(options.runUntil()).isNull();
    assertThat(options.deltaOverlap()).isEqualTo(Duration.ofMinutes(15));
  }

  @Test
  void rejectsUnsafeSchemaNames() {
    assertThatThrownBy(() -> CcdDataMigrationTaskOptions.builder(List.of("TestCase"))
        .targetSchema("ccd;drop schema ccd")
        .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("targetSchema");
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
  void rejectsNegativeDeltaOverlap() {
    assertThatThrownBy(() -> CcdDataMigrationTaskOptions.builder(List.of("TestCase"))
        .deltaOverlap(Duration.ofMillis(-1))
        .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("deltaOverlap");
  }
}

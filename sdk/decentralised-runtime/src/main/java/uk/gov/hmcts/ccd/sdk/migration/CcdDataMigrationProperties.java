package uk.gov.hmcts.ccd.sdk.migration;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ccd.data-migration")
public class CcdDataMigrationProperties {

  private boolean enabled;
  private String taskName = "ccd-data-migration";
  private List<String> caseTypeIds = new ArrayList<>();
  private int batchSize = 100;
  private long caseRevisionOffset = 1_000_000_000L;
  private int maxBatchesPerRun = Integer.MAX_VALUE;
  private Duration maxRunTime;
  private LocalDateTime runUntil;
  private Duration deltaOverlap = Duration.ofMinutes(15);
  private CcdDataMigrationValidationMode validationMode = CcdDataMigrationValidationMode.DELTA_ONLY;

  CcdDataMigrationTaskOptions toOptions() {
    if (caseTypeIds == null || caseTypeIds.isEmpty()) {
      throw new IllegalStateException("ccd.data-migration.case-type-ids must be configured when enabled");
    }

    return CcdDataMigrationTaskOptions.builder(caseTypeIds)
        .taskName(taskName)
        .batchSize(batchSize)
        .caseRevisionOffset(caseRevisionOffset)
        .maxBatchesPerRun(maxBatchesPerRun)
        .maxRunTime(maxRunTime)
        .runUntil(runUntil)
        .deltaOverlap(deltaOverlap)
        .validationMode(validationMode)
        .build();
  }
}

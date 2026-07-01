package uk.gov.hmcts.ccd.sdk.migration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ccd.data-migration")
public class CcdDataMigrationProperties {

  private boolean enabled;
  private String taskName = "ccd-data-migration";
  private CcdDataMigrationMode mode = CcdDataMigrationMode.PRELOAD_EVENTS;
  private List<String> caseTypeIds = new ArrayList<>();
  private int eventBatchSize = 10_000;
  private long caseRevisionOffset = 1_000_000_000L;
  private int maxBatchesPerRun = Integer.MAX_VALUE;
  private Duration maxRunTime;

  CcdDataMigrationTaskOptions toOptions() {
    if (caseTypeIds == null || caseTypeIds.isEmpty()) {
      throw new IllegalStateException("ccd.data-migration.case-type-ids must be configured when enabled");
    }

    return CcdDataMigrationTaskOptions.builder(caseTypeIds)
        .taskName(taskName)
        .mode(mode)
        .eventBatchSize(eventBatchSize)
        .caseRevisionOffset(caseRevisionOffset)
        .maxBatchesPerRun(maxBatchesPerRun)
        .maxRunTime(maxRunTime)
        .build();
  }
}

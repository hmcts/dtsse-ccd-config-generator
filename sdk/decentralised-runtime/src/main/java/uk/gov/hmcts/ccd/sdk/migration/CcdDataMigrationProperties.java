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
  private int eventIdWindowSize = 1_000_000;
  private int significantItemIdWindowSize = 100_000;
  private long caseRevisionOffset = 1_000_000_000L;
  private int maxBatchesPerRun = Integer.MAX_VALUE;
  private Duration maxRunTime;
  private Duration statementTimeout = Duration.ofMinutes(10);
  private String sourceJurisdiction;

  CcdDataMigrationTaskOptions toOptions() {
    if (caseTypeIds == null || caseTypeIds.isEmpty()) {
      throw new IllegalStateException("ccd.data-migration.case-type-ids must be configured when enabled");
    }
    if (sourceJurisdiction == null || sourceJurisdiction.isBlank()) {
      throw new IllegalStateException("ccd.data-migration.source-jurisdiction must be configured when enabled");
    }

    return CcdDataMigrationTaskOptions.builder(caseTypeIds)
        .taskName(taskName)
        .mode(mode)
        .eventIdWindowSize(eventIdWindowSize)
        .significantItemIdWindowSize(significantItemIdWindowSize)
        .caseRevisionOffset(caseRevisionOffset)
        .maxBatchesPerRun(maxBatchesPerRun)
        .maxRunTime(maxRunTime)
        .statementTimeout(statementTimeout)
        .sourceJurisdiction(sourceJurisdiction)
        .build();
  }

}

package uk.gov.hmcts.ccd.sdk.retention;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

@Slf4j
public class CaseRetentionTask implements Runnable {
  private final CaseRetentionService caseRetentionService;
  private final RetentionProperties properties;

  public CaseRetentionTask(CaseRetentionService caseRetentionService, RetentionProperties properties) {
    this.caseRetentionService = caseRetentionService;
    this.properties = properties;
  }

  @Override
  public void run() {
    RetentionTaskResult result = caseRetentionService.run(
        parseCaseTypes(properties.getDisposal().getCaseTypeIds()),
        parseCaseTypes(properties.getDisposal().getSimulationCaseTypeIds()),
        properties.getDisposal().getBatchSize()
    );
    log.info("Case retention task complete: deleted={}, simulated={}, skipped={}",
        result.deletedCases(), result.simulatedCases(), result.skippedCases());
  }

  private Set<String> parseCaseTypes(String configuredCaseTypes) {
    if (!StringUtils.hasText(configuredCaseTypes)) {
      return Set.of();
    }

    return Arrays.stream(configuredCaseTypes.split(","))
        .map(String::trim)
        .filter(StringUtils::hasText)
        .collect(Collectors.toSet());
  }
}

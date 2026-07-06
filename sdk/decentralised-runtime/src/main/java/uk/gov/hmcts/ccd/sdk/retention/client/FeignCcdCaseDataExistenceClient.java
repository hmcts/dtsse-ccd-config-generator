package uk.gov.hmcts.ccd.sdk.retention.client;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import uk.gov.hmcts.ccd.sdk.retention.CcdCaseDataExistenceClient;

public class FeignCcdCaseDataExistenceClient implements CcdCaseDataExistenceClient {
  private final CcdDataStoreRetentionFeignClient feignClient;

  public FeignCcdCaseDataExistenceClient(CcdDataStoreRetentionFeignClient feignClient) {
    this.feignClient = feignClient;
  }

  @Override
  public Map<Long, Boolean> caseDataExists(String jurisdiction, Collection<Long> caseReferences) {
    CcdCaseDataExistenceResponse response = feignClient.caseDataExists(new CcdCaseDataExistenceRequest(
        jurisdiction,
        caseReferences.stream().map(String::valueOf).toList()
    ));

    if (response == null || response.results() == null) {
      return Map.of();
    }

    return response.results().entrySet().stream()
        .collect(Collectors.toMap(entry -> Long.valueOf(entry.getKey()), Map.Entry::getValue));
  }
}

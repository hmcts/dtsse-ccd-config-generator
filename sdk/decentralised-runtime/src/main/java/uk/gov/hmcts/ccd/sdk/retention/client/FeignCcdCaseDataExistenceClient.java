package uk.gov.hmcts.ccd.sdk.retention.client;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import uk.gov.hmcts.ccd.sdk.retention.CcdCaseDataExistenceClient;
import uk.gov.hmcts.ccd.sdk.retention.RetentionCaseData;

@Slf4j
public class FeignCcdCaseDataExistenceClient implements CcdCaseDataExistenceClient {
  private final CcdDataStoreRetentionFeignClient feignClient;
  private final RetentionSystemUserTokenProvider systemUserTokenProvider;

  public FeignCcdCaseDataExistenceClient(CcdDataStoreRetentionFeignClient feignClient,
                                         RetentionSystemUserTokenProvider systemUserTokenProvider) {
    this.feignClient = feignClient;
    this.systemUserTokenProvider = systemUserTokenProvider;
  }

  @Override
  public Map<Long, Boolean> caseDataExists(Collection<RetentionCaseData> cases) {
    RetentionSystemUserTokenProvider.SystemUser systemUser = systemUserTokenProvider.systemUser();
    Map<Long, Boolean> results = new LinkedHashMap<>();

    for (RetentionCaseData retentionCase : cases) {
      results.put(retentionCase.reference(), caseDataExists(retentionCase, systemUser));
    }

    return results;
  }

  private boolean caseDataExists(RetentionCaseData retentionCase,
                                 RetentionSystemUserTokenProvider.SystemUser systemUser) {
    try {
      feignClient.retrieveCase(
          systemUser.authorization(),
          systemUser.uid(),
          retentionCase.jurisdiction(),
          retentionCase.caseTypeId(),
          String.valueOf(retentionCase.reference())
      );
      return true;
    } catch (feign.FeignException.NotFound exception) {
      return false;
    } catch (feign.FeignException exception) {
      log.error("CCD existence check failed for case {}", retentionCase.reference(), exception);
      return true;
    }
  }
}

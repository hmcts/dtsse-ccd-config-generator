package uk.gov.hmcts.ccd.sdk.retention;

import static uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy.DISPOSAL_STATE_ID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy;

@Slf4j
@RequiredArgsConstructor
final class RetainAndDisposeCaseReconciler {

  private final RetainAndDisposePolicy policy;
  private final RetainAndDisposeRepository repository;
  private final CoreCaseDataRetainAndDisposeClient ccdClient;
  private final TransactionTemplate transaction;

  void reconcile(RetainAndDisposeCase disposalCase) {
    if (ccdClient.exists(disposalCase)) {
      log.info("Retaining local case still present in CCD caseReference={} caseTypeId={}",
          disposalCase.reference(), disposalCase.caseTypeId());
      return;
    }
    transaction.executeWithoutResult(status -> {
      policy.dispose(disposalCase.reference());
      repository.deleteCase(disposalCase.reference(), disposalCase.caseTypeId(), DISPOSAL_STATE_ID);
    });
    log.info("Deleted local case after CCD returned not found caseReference={} caseTypeId={}",
        disposalCase.reference(), disposalCase.caseTypeId());
  }
}

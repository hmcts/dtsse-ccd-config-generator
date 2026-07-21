package uk.gov.hmcts.ccd.sdk.retention;

import static uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy.DISPOSAL_STATE_ID;

import lombok.RequiredArgsConstructor;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy;

@RequiredArgsConstructor
final class RetainAndDisposeCaseReconciler {

  private final RetainAndDisposePolicy policy;
  private final RetainAndDisposeRepository repository;
  private final CoreCaseDataRetainAndDisposeClient ccdClient;
  private final TransactionTemplate transaction;

  void reconcile(RetainAndDisposeCase disposalCase) {
    if (ccdClient.exists(disposalCase)) {
      return;
    }
    transaction.executeWithoutResult(status -> {
      policy.dispose(disposalCase.reference());
      repository.deleteCase(disposalCase.reference(), disposalCase.caseTypeId(), DISPOSAL_STATE_ID);
    });
  }
}

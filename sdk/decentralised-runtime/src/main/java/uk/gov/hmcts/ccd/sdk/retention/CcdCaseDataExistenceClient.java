package uk.gov.hmcts.ccd.sdk.retention;

import java.util.Collection;
import java.util.Map;

public interface CcdCaseDataExistenceClient {

  Map<Long, Boolean> caseDataExists(Collection<RetentionCaseData> cases);
}

package uk.gov.hmcts.ccd.sdk.retention;

import java.util.Collection;
import java.util.Map;

public interface CcdCaseDataExistenceClient {

  Map<Long, Boolean> caseDataExists(String jurisdiction, Collection<Long> caseReferences);
}

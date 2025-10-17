package uk.gov.hmcts.ccd.sdk;

import java.util.Objects;
import java.util.function.Supplier;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedSubmitEventResponse;

record CaseSubmissionOutcome(long caseDataId,
                             Supplier<DecentralisedSubmitEventResponse> responseSupplier) {
  CaseSubmissionOutcome {
    Objects.requireNonNull(responseSupplier, "responseSupplier");
  }

  DecentralisedSubmitEventResponse buildResponse() {
    return responseSupplier.get();
  }
}

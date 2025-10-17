package uk.gov.hmcts.ccd.sdk;

import java.util.Objects;
import java.util.Optional;
import uk.gov.hmcts.ccd.data.persistence.dto.DecentralisedSubmitEventResponse;

record CaseSubmissionResponse(DecentralisedSubmitEventResponse response, Optional<Runnable> postCommit) {
  CaseSubmissionResponse {
    Objects.requireNonNull(response, "response");
    Objects.requireNonNull(postCommit, "postCommit");
  }

  CaseSubmissionResponse(DecentralisedSubmitEventResponse response) {
    this(response, Optional.empty());
  }
}

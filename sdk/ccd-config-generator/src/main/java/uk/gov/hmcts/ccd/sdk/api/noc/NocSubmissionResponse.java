package uk.gov.hmcts.ccd.sdk.api.noc;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NocSubmissionResponse(
    @JsonProperty("approval_status") String approvalStatus
) {

  public static NocSubmissionResponse approved() {
    return new NocSubmissionResponse("APPROVED");
  }
}

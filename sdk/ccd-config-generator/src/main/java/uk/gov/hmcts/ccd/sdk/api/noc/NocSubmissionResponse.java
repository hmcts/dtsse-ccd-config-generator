package uk.gov.hmcts.ccd.sdk.api.noc;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NocSubmissionResponse(
    @JsonProperty("approval_status") String approvalStatus,
    String status,
    String message,
    String code,
    List<String> errors
) {

  public static NocSubmissionResponse approved() {
    return new NocSubmissionResponse("APPROVED", null, null, null, null);
  }

  public static NocSubmissionResponse invalid(String code, String message) {
    return new NocSubmissionResponse(null, "BAD_REQUEST", message, code, List.of());
  }

  @JsonIgnore
  public boolean isApproved() {
    return code == null;
  }
}

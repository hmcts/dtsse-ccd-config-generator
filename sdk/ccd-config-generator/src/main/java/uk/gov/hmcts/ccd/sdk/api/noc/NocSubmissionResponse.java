package uk.gov.hmcts.ccd.sdk.api.noc;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NocSubmissionResponse(
    @JsonProperty("approval_status") String approvalStatus,
    @JsonProperty("case_role") String caseRole,
    @JsonProperty("status_message") String statusMessage,
    String status,
    String message,
    String code,
    List<String> errors
) {

  public static NocSubmissionResponse approved(String caseRole) {
    return new NocSubmissionResponse(
        "APPROVED",
        caseRole,
        "Notice of request has been successfully submitted.",
        null,
        null,
        null,
        null
    );
  }

  public static NocSubmissionResponse pending(String caseRole) {
    return new NocSubmissionResponse(
        "PENDING",
        caseRole,
        "Notice of request has been successfully submitted.",
        null,
        null,
        null,
        null
    );
  }

  public static NocSubmissionResponse invalid(NocError error, Object... args) {
    return invalid(error.code(), error.message(args));
  }

  public static NocSubmissionResponse invalid(String code, String message) {
    return new NocSubmissionResponse(null, null, null, "BAD_REQUEST", message, code, List.of());
  }

  @JsonIgnore
  public boolean isAccepted() {
    return code == null;
  }
}

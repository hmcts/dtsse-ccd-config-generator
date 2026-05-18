package uk.gov.hmcts.ccd.sdk.api.noc;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NocAnswersResponse(
    @JsonProperty("status_message") String statusMessage,
    String status,
    String message,
    String code,
    List<String> errors
) {

  public static NocAnswersResponse verified() {
    return new NocAnswersResponse("Notice of Change answers verified successfully", null, null, null, null);
  }

  public static NocAnswersResponse invalid(String code, String message) {
    return new NocAnswersResponse(null, "BAD_REQUEST", message, code, List.of());
  }

  @JsonIgnore
  public boolean isValid() {
    return code == null;
  }
}

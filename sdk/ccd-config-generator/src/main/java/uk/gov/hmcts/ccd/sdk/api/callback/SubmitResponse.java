package uk.gov.hmcts.ccd.sdk.api.callback;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * Response returned from decentralised submit handlers.
 */
@Builder
@Data
public class SubmitResponse<State> {

  public static <State> SubmitResponse<State> defaultResponse() {
    return SubmitResponse.<State>builder().build();
  }

  private String confirmationHeader;

  private String confirmationBody;

  private List<String> errors;

  private List<String> warnings;

  private List<String> ignoreWarning;

  private State state;

  private String caseSecurityClassification;
}

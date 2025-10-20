package uk.gov.hmcts.ccd.sdk.api.callback;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * Response returned from decentralised submit handlers.
 */
@Builder
@Data
public class SubmitResponse<S> {

  public static <S> SubmitResponse<S> defaultResponse() {
    return SubmitResponse.<S>builder().build();
  }

  private String confirmationHeader;

  private String confirmationBody;

  private List<String> errors;

  private List<String> warnings;

  private List<String> ignoreWarning;

  private S state;

  private String caseSecurityClassification;
}

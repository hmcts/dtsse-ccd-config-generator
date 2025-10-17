package uk.gov.hmcts.ccd.sdk.api.callback;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * Response returned from decentralised submit handlers.
 */
@Builder
@Data
public class SubmitResponse {

  private String confirmationHeader;

  private String confirmationBody;

  private List<String> errors;
}

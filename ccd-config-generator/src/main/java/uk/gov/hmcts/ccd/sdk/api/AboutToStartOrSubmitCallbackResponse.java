package uk.gov.hmcts.ccd.sdk.api;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class AboutToStartOrSubmitCallbackResponse<T, S> {
  private T data;

  private List<String> errors;

  private List<String> warnings;

  private S state;

}

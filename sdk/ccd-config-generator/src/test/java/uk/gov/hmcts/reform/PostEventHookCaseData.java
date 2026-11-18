package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;

@Data
public class PostEventHookCaseData {

  @CCD(label = "Set by each callback")
  private String note;

  @CCD(label = "Appended to by each post-event hook")
  private String hookTrail;
}

package uk.gov.hmcts.reform;

import uk.gov.hmcts.ccd.sdk.api.CCD;

public class RenamedFixedListCaseData {

  @CCD(label = "A renamed fixed list")
  private RenamedFixedListChoice choice;
}

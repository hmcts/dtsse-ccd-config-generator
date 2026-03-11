package uk.gov.hmcts.reform.optionallist;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.type.Flags;

@Data
public class TestParty {
  private String firstName;
  private String lastName;

  @CCD(label = "Party Flags")
  private Flags flags;
}

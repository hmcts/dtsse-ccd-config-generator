package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;

@Data
public class NoImplicitTabReadCaseData {

  @CCD(label = "Edited by both roles")
  private String sharedField;

  @CCD(label = "Shown on the overview tab, granted to one role")
  private String adminOnlyPanel;

  @CCD(label = "Searchable by both roles")
  private String searchableField;
}

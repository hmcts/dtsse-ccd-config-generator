package uk.gov.hmcts.reform.fpl.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.type.YesOrNo;
import uk.gov.hmcts.reform.fpl.access.BulkScan;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LocationPreferences {
  @CCD(label = "Preference for local hearing", access = {BulkScan.class})
  private YesOrNo local;

  @CCD(label = "Preference for online hearing", access = {BulkScan.class})
  private YesOrNo online;

}

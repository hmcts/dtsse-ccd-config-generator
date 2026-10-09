package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.reform.fpl.access.SolicitorAccess;

@Data
public class CallbackUrlsCaseData {

  @CCD(label = "First", access = {SolicitorAccess.class})
  private String first;

  @CCD(label = "Second", access = {SolicitorAccess.class})
  private String second;

  @CCD(label = "Third", access = {SolicitorAccess.class})
  private String third;
}

package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.type.FieldType;

@Data
public class OwnHistoryViewerCaseData {

  @CCD(label = "Edited by both roles")
  private String sharedField;

  @CCD(label = " ", typeOverride = FieldType.CaseHistoryViewer)
  private String auditTrail;
}

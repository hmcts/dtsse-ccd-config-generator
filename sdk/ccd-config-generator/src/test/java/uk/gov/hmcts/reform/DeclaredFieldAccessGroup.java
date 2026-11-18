package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;

@Data
public class DeclaredFieldAccessGroup {

  @CCD(label = "Inside a parent that declares access, declares none itself")
  private String unwrappedChild;
}

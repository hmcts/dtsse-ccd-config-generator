package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;

/**
 * Fields for {@link FieldExtrasCaseType}.
 */
@Data
public class FieldExtrasCaseData {

  @CCD(label = "An optional field")
  private String optionalField;

  @CCD(label = "A readonly field")
  private String readonlyField;

  @CCD(label = "A no-summary field")
  private String noSummaryField;

  @CCD(label = "A field carrying a positional default")
  private String positionalDefaultField;
}

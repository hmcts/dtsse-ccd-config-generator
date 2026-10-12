package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;

/**
 * Data for {@link SearchCasesRoleCaseType}, exercising the role/use-case columns of the
 * {@code SearchCasesResultFields} sheet.
 */
@Data
public class SearchCasesRoleCaseData {

  @CCD(label = "Case name")
  private String caseName;
}

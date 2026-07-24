package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;

/**
 * Data for {@link SearchExtrasCaseType}. {@link #applicant} is a complex field whose leaves are
 * exposed one at a time in the search/workbasket sheets via {@code ListElementCode}; {@link #caseName}
 * is a plain scalar carrying {@code FieldShowCondition}/{@code ResultsOrdering}.
 */
@Data
public class SearchExtrasCaseData {

  @CCD(label = "Case name")
  private String caseName;

  @CCD(label = "Applicant")
  private SearchExtrasApplicant applicant;
}

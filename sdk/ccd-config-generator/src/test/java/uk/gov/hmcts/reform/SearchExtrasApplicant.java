package uk.gov.hmcts.reform;

import lombok.Builder;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * A small complex type for {@link SearchExtrasCaseType}: its leaves ({@code forename}/{@code surname})
 * are what the search/workbasket sheets target one at a time via {@code ListElementCode}.
 */
@Data
@Builder
@ComplexType(name = "SearchExtrasApplicant")
public class SearchExtrasApplicant {

  @CCD(label = "Forename")
  private String forename;

  @CCD(label = "Surname")
  private String surname;
}

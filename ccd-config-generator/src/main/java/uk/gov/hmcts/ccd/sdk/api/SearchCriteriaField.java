package uk.gov.hmcts.ccd.sdk.api;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class SearchCriteriaField {
  private String caseTypeId;
  private String otherCaseReference;
}

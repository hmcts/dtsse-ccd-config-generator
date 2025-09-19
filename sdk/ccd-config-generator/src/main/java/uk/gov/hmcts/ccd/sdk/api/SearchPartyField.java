package uk.gov.hmcts.ccd.sdk.api;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SearchPartyField {
  private String caseTypeId;
  private String searchPartyCollectionFieldName;
  private String searchPartyName;
  private String searchPartyEmailAddress;
  private String searchPartyAddressLine1;
  private String searchPartyPostCode;
  private String searchPartyDOB;
  private String searchPartyDOD;
}

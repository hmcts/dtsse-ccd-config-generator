package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@NoArgsConstructor
@Builder
@Data
@ComplexType(name = "CaseAccessGroup")
public class CaseAccessGroup {

  private String caseAccessGroupType;
  private String caseAccessGroupId;

  @JsonCreator
  public CaseAccessGroup(String caseAccessGroupType, String caseAccessGroupId) {
    this.caseAccessGroupType = caseAccessGroupType;
    this.caseAccessGroupId = caseAccessGroupId;
  }
}

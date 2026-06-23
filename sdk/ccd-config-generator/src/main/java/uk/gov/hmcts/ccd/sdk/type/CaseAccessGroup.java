package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@NoArgsConstructor
@Builder
@Data
@ComplexType(name = "CaseAccessGroup", generate = false)
public class CaseAccessGroup {

  private String caseAccessGroupType;
  private String caseAccessGroupId;

  @JsonCreator
  public CaseAccessGroup(@JsonProperty("caseAccessGroupType") String caseAccessGroupType,
                         @JsonProperty("caseAccessGroupId") String caseAccessGroupId) {
    this.caseAccessGroupType = caseAccessGroupType;
    this.caseAccessGroupId = caseAccessGroupId;
  }
}

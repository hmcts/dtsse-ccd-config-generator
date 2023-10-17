package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;


@NoArgsConstructor
@Builder
@Data
@ComplexType(name = "Search criteria", generate = false)
public class SearchCriteria {

  @JsonProperty("otherCaseReferences")
  private List<List> otherCaseReferences;

  @JsonProperty("parties")
  private List<SearchParty> parties;

  @JsonCreator
  public SearchCriteria(@JsonProperty("otherCaseReferences") List<List> otherCaseReferences,
                        @JsonProperty("roleOnCase") List<SearchParty> parties) {
    this.otherCaseReferences = otherCaseReferences;
    this.parties = parties;
  }

}

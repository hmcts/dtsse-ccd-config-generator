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
@ComplexType(name = "SearchCriteria")
public class SearchCriteria {

  @JsonProperty("OtherCaseReferences")
  private List<ListValue<String>> otherCaseReferences;

  @JsonProperty("SearchParties")
  private List<ListValue<SearchParty>> parties;

  @JsonCreator
  public SearchCriteria(@JsonProperty("OtherCaseReferences") List<ListValue<String>> otherCaseReferences,
                        @JsonProperty("SearchParties") List<ListValue<SearchParty>> parties) {
    this.otherCaseReferences = otherCaseReferences;
    this.parties = parties;
  }

}

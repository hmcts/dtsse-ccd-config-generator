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
  private List<ListValue<List>> otherCaseReferences;

  @JsonProperty("parties")
  private List<ListValue<SearchParty>> parties;

  @JsonCreator
  public SearchCriteria(@JsonProperty("otherCaseReferences") List<ListValue<List>> otherCaseReferences,
                        @JsonProperty("roleOnCase") List<ListValue<SearchParty>> parties) {
    this.otherCaseReferences = otherCaseReferences;
    this.parties = parties;
  }

}

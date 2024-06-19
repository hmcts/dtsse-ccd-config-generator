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

  @JsonProperty("OtherCaseReference")
  private List<ListValue<String>> otherCaseReference;

  @JsonCreator
  public SearchCriteria(@JsonProperty("OtherCaseReference") List<ListValue<String>> otherCaseReference) {
    this.otherCaseReference = otherCaseReference;
  }

}

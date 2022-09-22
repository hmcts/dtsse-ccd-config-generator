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
@ComplexType(name = "CaseLink", generate = false)
public class CaseLink {

  @JsonProperty("CaseReference")
  private String caseReference;

  @JsonProperty("CaseDerp")
  private String caseDerp;

  @JsonCreator
  public CaseLink(@JsonProperty("CaseReference") String caseReference, @JsonProperty("CaseDerp") String caseDerp) {
    this.caseReference = caseReference;
    this.caseDerp = caseDerp;
  }
}

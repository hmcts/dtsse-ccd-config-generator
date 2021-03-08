package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@Data
@ComplexType(name = "CaseLink", generate = false)
public class CaseLink {

  @JsonProperty("CaseReference")
  private final String caseReference;

}

package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@Builder
@Data
@Jacksonized
@ComplexType(name = "CaseLink", generate = false)
public class CaseLink {

  @JsonProperty("CaseReference")
  private final String caseReference;

}

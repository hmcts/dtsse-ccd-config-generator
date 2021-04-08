package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@Data
@Builder
@ComplexType(name = "Organisation", generate = false)
public class Organisation {
  @JsonProperty("OrganisationID")
  private final String organisationId;
}

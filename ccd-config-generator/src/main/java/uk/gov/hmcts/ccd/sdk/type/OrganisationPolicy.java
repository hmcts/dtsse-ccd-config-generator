package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@Data
@ComplexType(name = "OrganisationPolicy", generate = false)
@Builder
public class OrganisationPolicy<R> {

  @JsonProperty("Organisation")
  private final Organisation organisation;

  @JsonProperty("OrgPolicyReference")
  private final String orgPolicyReference;

  @JsonProperty("OrgPolicyCaseAssignedRole")
  private final R orgPolicyCaseAssignedRole;

}

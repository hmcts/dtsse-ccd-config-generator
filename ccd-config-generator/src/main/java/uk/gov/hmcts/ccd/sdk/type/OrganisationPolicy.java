package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

@AllArgsConstructor
@Builder
@Data
@Jacksonized
@ComplexType(name = "OrganisationPolicy", generate = false)
public class OrganisationPolicy<R extends HasRole> {

  @JsonProperty("Organisation")
  private final Organisation organisation;

  @JsonProperty("PreviousOrganisations")
  private final Set<PreviousOrganisation> previousOrganisations;

  @JsonProperty("OrgPolicyReference")
  private final String orgPolicyReference;

  @JsonProperty("PrepopulateToUsersOrganisation")
  private final YesOrNo prepopulateToUsersOrganisation;

  @JsonProperty("OrgPolicyCaseAssignedRole")
  private final R orgPolicyCaseAssignedRole;
}

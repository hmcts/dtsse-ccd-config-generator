package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

@NoArgsConstructor
@Builder
@Data
@ComplexType(name = "OrganisationPolicy", generate = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrganisationPolicy<R extends HasRole> {

  @JsonProperty("Organisation")
  private Organisation organisation;

  /**
   * Newest first, as Notice of Change stores them. Jackson reads them into a {@link LinkedHashSet},
   * which keeps that order; code that builds or copies this set should also use a
   * {@link LinkedHashSet} so the newest-first order CCD stores is kept.
   */
  @JsonProperty("PreviousOrganisations")
  @JsonDeserialize(as = LinkedHashSet.class)
  private Set<PreviousOrganisationCollectionItem> previousOrganisations;

  @JsonProperty("OrgPolicyReference")
  private String orgPolicyReference;

  @JsonProperty("PrepopulateToUsersOrganisation")
  private YesOrNo prepopulateToUsersOrganisation;

  @JsonProperty("OrgPolicyCaseAssignedRole")
  private R orgPolicyCaseAssignedRole;

  /**
   * The user who raised the Notice of Change that last added an organisation, written by
   * aac-manage-case-assignment.
   */
  @JsonProperty("LastNoCRequestedBy")
  private String lastNoCRequestedBy;

  public OrganisationPolicy(
      Organisation organisation,
      Set<PreviousOrganisationCollectionItem> previousOrganisations,
      String orgPolicyReference,
      YesOrNo prepopulateToUsersOrganisation,
      R orgPolicyCaseAssignedRole
  ) {
    this(organisation, previousOrganisations, orgPolicyReference, prepopulateToUsersOrganisation,
        orgPolicyCaseAssignedRole, null);
  }

  @JsonCreator
  public OrganisationPolicy(
      @JsonProperty("Organisation") Organisation organisation,
      @JsonProperty("PreviousOrganisations") Set<PreviousOrganisationCollectionItem> previousOrganisations,
      @JsonProperty("OrgPolicyReference") String orgPolicyReference,
      @JsonProperty("PrepopulateToUsersOrganisation") YesOrNo prepopulateToUsersOrganisation,
      @JsonProperty("OrgPolicyCaseAssignedRole") R orgPolicyCaseAssignedRole,
      @JsonProperty("LastNoCRequestedBy") String lastNoCRequestedBy
  ) {
    this.organisation = organisation;
    this.previousOrganisations = previousOrganisations;
    this.orgPolicyReference = orgPolicyReference;
    this.prepopulateToUsersOrganisation = prepopulateToUsersOrganisation;
    this.orgPolicyCaseAssignedRole = orgPolicyCaseAssignedRole;
    this.lastNoCRequestedBy = lastNoCRequestedBy;
  }
}

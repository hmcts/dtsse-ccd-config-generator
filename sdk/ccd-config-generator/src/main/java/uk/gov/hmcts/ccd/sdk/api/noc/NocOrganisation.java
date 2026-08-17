package uk.gov.hmcts.ccd.sdk.api.noc;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NocOrganisation(
    @JsonProperty("OrganisationID") String organisationId,
    @JsonProperty("OrganisationName") String organisationName
) {
}

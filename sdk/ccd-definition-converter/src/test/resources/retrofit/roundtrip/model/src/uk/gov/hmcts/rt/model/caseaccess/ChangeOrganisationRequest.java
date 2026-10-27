package uk.gov.hmcts.rt.model.caseaccess;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * The team's OWN {@code ChangeOrganisationRequest} — same CCD shape as the SDK's predefined type of
 * the same complex-type ID, but a distinct Java class whose {@code OrganisationToAdd} member is the
 * team's {@link Organisation} (getter {@code getOrganisationID}), not the SDK's. A
 * {@code CaseEventToComplexTypes} chain {@code OrganisationToAdd.OrganisationID} must walk the team's
 * classes; binding to the SDK predefined types emits {@code Organisation::getOrganisationId} on a
 * scope typed as the team's {@code Organisation} — a compile error the round-trip catches.
 *
 * <p>{@code @ComplexType(generate = false)}: the {@code ChangeOrganisationRequest} complex type is an
 * SDK-predefined shape, so the SDK emits no ComplexTypes rows for it — matching the input.
 */
@Data
@ComplexType(name = "ChangeOrganisationRequest", generate = false)
public class ChangeOrganisationRequest {

  @JsonProperty("OrganisationToAdd")
  private Organisation organisationToAdd;

  @JsonProperty("CaseRoleId")
  private String caseRoleId;
}

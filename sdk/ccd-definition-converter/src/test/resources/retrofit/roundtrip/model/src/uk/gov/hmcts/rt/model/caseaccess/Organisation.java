package uk.gov.hmcts.rt.model.caseaccess;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * The team's OWN {@code Organisation} class — same CCD shape as the SDK's
 * {@code uk.gov.hmcts.ccd.sdk.type.Organisation} (complex-type ID {@code Organisation}), but its Java
 * field is {@code organisationID} (getter {@code getOrganisationID}) where the SDK's is
 * {@code organisationId} (getter {@code getOrganisationId}). A {@code CaseEventToComplexTypes} member
 * chain reaching this class must reference {@code getOrganisationID}; binding to the SDK class would
 * emit {@code getOrganisationId} — a method reference the team's field type does not expose (probate
 * conflict #4 / prl bug class 6). This is the retrofit binder's SDK-type-vs-model-type defect.
 *
 * <p>{@code @ComplexType(generate = false)} because the definition store already knows the
 * {@code Organisation} complex type (it is an SDK-predefined shape the team shadows with its own
 * class), so the SDK emits no {@code ComplexTypes/Organisation.json} — matching the input, which
 * carries no ComplexTypes rows for it.
 */
@Data
@ComplexType(name = "Organisation", generate = false)
public class Organisation {

  @JsonProperty("OrganisationID")
  private String organisationID;

  @JsonProperty("OrganisationName")
  private String organisationName;
}

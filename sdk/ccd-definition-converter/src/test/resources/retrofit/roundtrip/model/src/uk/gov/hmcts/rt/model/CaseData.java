package uk.gov.hmcts.rt.model;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.rt.enums.ClaimType;
import uk.gov.hmcts.rt.model.caseaccess.ChangeOrganisationRequest;
import uk.gov.hmcts.rt.model.common.DocItem;
import uk.gov.hmcts.rt.model.common.Party;
import uk.gov.hmcts.rt.model.event.HearingData;

/**
 * A hand-written fake team CaseData for the phase-2 retrofit round-trip proof. Deliberately carries
 * NO {@code @CCD} annotations — the retrofit patch adds them — but does carry the Jackson/Lombok
 * shape a real model has (getters via {@code @Data}, a Lombok {@code @Builder}/
 * {@code @AllArgsConstructor} like SSCS's {@code SscsCaseData}, a {@code @JsonUnwrapped} event-data
 * sub-object, a concrete collection wrapper, a reused enum, a nested complex type, plus a model-only
 * field the definition has no row for).
 */
@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class CaseData {

  // EXACT match: String -> Text, gains @CCD(label, access) from the patch.
  private String applicantName;

  // TYPE_CONFLICT: the definition says Email; a String infers to Text, so the patch adds
  // @CCD(typeOverride = FieldType.Email).
  private String applicantEmail;

  // Reused model enum: the definition's FixedList "ClaimType" resolves to this existing enum, so no
  // fresh enum is generated; the patch adds @CCD(typeOverride = FieldType.FixedList).
  private ClaimType claimType;

  // Concrete-wrapper collection (decision 8): List<DocItem> mis-resolves to DocItem, so the patch
  // adds @CCD(typeOverride = FieldType.Collection, typeParameterOverride = "Document").
  private List<DocItem> documents;

  // Nested complex type: its members are annotated on the Party class.
  private Party respondent;

  // Prefixed @JsonUnwrapped sub-object: flattens to hearingType / hearingLength.
  @JsonUnwrapped(prefix = "hearing")
  private HearingData hearingData;

  // A complex field whose declared type is the TEAM's own ChangeOrganisationRequest (not the SDK
  // predefined type of the same complex-type ID). Its CaseEventToComplexTypes member chain
  // (OrganisationToAdd.OrganisationID / .OrganisationName / CaseRoleId) must bind to the team's
  // classes and their real getters (getOrganisationID) — binding to the SDK class emits
  // getOrganisationId on a team-typed scope, a compile error. Exercises the SDK-type-vs-model-type
  // binding defect (probate conflict #4 / prl bug class 6).
  private ChangeOrganisationRequest changeOrganisationRequestField;

  // UNMATCHED_JAVA_FIELD: no definition row, so the patch adds @CCD(ignore = true) to stop the SDK
  // reflecting it into a spurious CaseField.
  private String internalScratch;
}

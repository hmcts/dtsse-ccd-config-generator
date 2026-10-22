package uk.gov.hmcts.b4.model;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;

/**
 * A fake team CaseData mirroring SSCS's Bug4 shape: prefix-less {@code @JsonUnwrapped} parents whose
 * Lombok getter is suppressed with {@code @Getter(AccessLevel.NONE)}, one with a differently-named
 * hand-written accessor (SSCS's {@code getSscsFinalDecisionCaseData}) and one with none — plus a
 * normal unwrapped parent whose getter IS generated. Only the latter's leaf can be placed via a typed
 * getter; the other two families are unplaceable and must skip + gap rather than emit a broken
 * {@code CaseData::getParent} reference.
 */
@Data
public class CaseData {

  // Suppressed getter + a DIFFERENTLY-named hand-written accessor (getRenamedParent maps to property
  // "renamedParent", which is not this field, so it cannot reproduce the prefix-less flattening).
  @JsonUnwrapped
  @Getter(AccessLevel.NONE)
  private CustomAccessorParent finalDecisionParent;

  // Suppressed getter + NO accessor at all.
  @JsonUnwrapped
  @Getter(AccessLevel.NONE)
  private NoAccessorParent deprecatedParent;

  // A normal prefix-less @JsonUnwrapped parent: @Data generates getWorkAllocation(), so its leaf IS
  // placeable via a typed getter.
  @JsonUnwrapped
  private WorkAllocationParent workAllocation;

  // The differently-named accessor for finalDecisionParent (like SSCS's getSscsFinalDecisionCaseData).
  public CustomAccessorParent getRenamedParent() {
    return finalDecisionParent;
  }
}

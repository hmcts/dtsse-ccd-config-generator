package uk.gov.hmcts.b4.model;

import lombok.Data;

/** Unwrapped parent whose leaf member the config would reach via a (suppressed) CaseData getter. */
@Data
public class CustomAccessorParent {
  private String finalDecisionField;
}

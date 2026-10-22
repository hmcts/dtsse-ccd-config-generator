package uk.gov.hmcts.b4.model;

import lombok.Data;

/** Unwrapped parent with no accessor on the root for its own field. */
@Data
public class NoAccessorParent {
  private String deprecatedField;
}

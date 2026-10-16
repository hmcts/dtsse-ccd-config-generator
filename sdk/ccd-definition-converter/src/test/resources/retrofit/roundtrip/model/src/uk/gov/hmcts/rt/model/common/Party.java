package uk.gov.hmcts.rt.model.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * A nested complex type the team owns. The definition's {@code Party} ComplexTypes rows carry
 * per-member ElementLabels, which the retrofit patch adds as {@code @CCD(label=...)} on each member.
 * {@code partyEmail} is an Email member (typeOverride) and is renamed via {@code @JsonProperty} to a
 * bean-legal Java field, so the patch leaves the existing {@code @JsonProperty} untouched.
 */
@Data
public class Party {

  private String fullName;

  @JsonProperty("partyEmail")
  private String email;
}

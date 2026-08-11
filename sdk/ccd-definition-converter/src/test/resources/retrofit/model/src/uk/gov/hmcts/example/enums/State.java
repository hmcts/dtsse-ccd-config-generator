package uk.gov.hmcts.example.enums;

import com.fasterxml.jackson.annotation.JsonProperty;
import uk.gov.hmcts.ccd.sdk.api.CCD;

/**
 * A State enum exercising the state-ID derivation: {@code OPEN} resolves to its constant name
 * {@code Open} (no @JsonProperty here means toString()), while {@code CASE_MANAGEMENT} carries a
 * {@code @JsonProperty} so its CCD id is {@code PREPARE_FOR_HEARING} (proposal decision 3 / StateId).
 *
 * <p>{@code STAYED} additionally carries a team-written {@code @CCD}, which the State-label pin must
 * leave alone — {@code @CCD} is not {@code @Repeatable}, so a second one would not compile. It also has
 * no definition state row, so it is the refusal case for the {@code ignore = true} pin;
 * {@code LEGACY_COMPOSITE} is the same divergence unannotated, and so takes the pin.
 */
public enum State {

  @JsonProperty("Open")
  OPEN,

  @JsonProperty("PREPARE_FOR_HEARING")
  CASE_MANAGEMENT,

  @CCD(label = "Stayed by the team")
  STAYED,

  LEGACY_COMPOSITE,

  CLOSED
}

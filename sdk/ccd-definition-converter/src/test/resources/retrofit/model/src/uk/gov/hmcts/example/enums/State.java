package uk.gov.hmcts.example.enums;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A State enum exercising the state-ID derivation: {@code OPEN} resolves to its constant name
 * {@code Open} (no @JsonProperty here means toString()), while {@code CASE_MANAGEMENT} carries a
 * {@code @JsonProperty} so its CCD id is {@code PREPARE_FOR_HEARING} (proposal decision 3 / StateId).
 */
public enum State {

  @JsonProperty("Open")
  OPEN,

  @JsonProperty("PREPARE_FOR_HEARING")
  CASE_MANAGEMENT,

  CLOSED
}

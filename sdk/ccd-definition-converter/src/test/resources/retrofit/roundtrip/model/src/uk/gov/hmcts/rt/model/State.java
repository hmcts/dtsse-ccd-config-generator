package uk.gov.hmcts.rt.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The team's own {@code State} enum, reused directly by retrofit (all three definition state IDs
 * resolve — proposal decision 3). {@code CASE_MANAGEMENT} carries {@code @JsonProperty} so its CCD
 * state ID is {@code PREPARE_FOR_HEARING} (the SDK's {@code StateId} honours it); the other two
 * resolve by their constant name.
 */
public enum State {

  @JsonProperty("Open")
  Open,

  @JsonProperty("PREPARE_FOR_HEARING")
  CASE_MANAGEMENT,

  Closed
}

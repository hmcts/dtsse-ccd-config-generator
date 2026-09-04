package uk.gov.hmcts.reform;

import com.fasterxml.jackson.annotation.JsonProperty;
import uk.gov.hmcts.ccd.sdk.api.CCD;

/**
 * State enum exercising {@code @JsonProperty} on the CCD state ID. {@code CASE_MANAGEMENT} carries
 * {@code @JsonProperty("PREPARE_FOR_HEARING")} (the FPL reconciliation pattern) so its resolved
 * state ID must be {@code PREPARE_FOR_HEARING} everywhere; {@code Open} has no {@code @JsonProperty}
 * so it must resolve to its constant name, unchanged.
 */
public enum JsonPropertyState {
  @CCD(label = "Open state")
  Open,

  @JsonProperty("PREPARE_FOR_HEARING")
  @CCD(label = "Case management state")
  CASE_MANAGEMENT
}

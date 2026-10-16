package uk.gov.hmcts.rt.enums;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.HasLabel;

/**
 * A reused fixed-list enum the team already owns. The definition's FixedList {@code ClaimType} maps
 * to it, so retrofit generates no fresh enum. Implements {@link HasLabel} and preserves each code
 * via {@code @JsonProperty} so the SDK's FixedListGenerator reproduces the ListElementCode/label
 * exactly.
 */
@Getter
@RequiredArgsConstructor
public enum ClaimType implements HasLabel {

  @JsonProperty("possession")
  POSSESSION("Possession claim"),

  @JsonProperty("money")
  MONEY("Money claim");

  private final String label;
}

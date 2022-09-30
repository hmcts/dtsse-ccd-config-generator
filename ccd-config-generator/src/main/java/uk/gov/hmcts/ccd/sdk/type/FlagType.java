package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
// TODO: Revisit this enum as this data needs to taken from Reference data
public enum FlagType {
  @JsonProperty("appellantAbroad")
  APPELLANT_ABROAD("Appellant abroad", "Appellant abroad","0"),

  @JsonProperty("Other")
  OTHER("Other", "Other","1");

  private final String reason;
  private final String description;
  private final String flagCode;


}

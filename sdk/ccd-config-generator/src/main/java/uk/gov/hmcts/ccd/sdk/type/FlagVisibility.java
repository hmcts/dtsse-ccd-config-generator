package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@Getter
@RequiredArgsConstructor
@ComplexType(generate = false)
public enum FlagVisibility {
  @JsonProperty("Internal")
  INTERNAL("Internal"),

  @JsonProperty("External")
  EXTERNAL("External");

  private final String value;
}

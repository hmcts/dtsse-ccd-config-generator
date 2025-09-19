package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@Getter
@RequiredArgsConstructor
@ComplexType(name = "YesOrNo", generate = false)
public enum YesOrNo {

  @JsonProperty("Yes")
  YES("Yes"),

  @JsonProperty("No")
  NO("No");

  private final String value;

  public static YesOrNo from(Boolean val) {
    return val ? YES : NO;
  }

  public boolean toBoolean() {
    return YES.name().equalsIgnoreCase(this.name());
  }
}

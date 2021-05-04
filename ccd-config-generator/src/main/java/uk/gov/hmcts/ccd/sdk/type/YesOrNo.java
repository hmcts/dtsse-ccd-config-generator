package uk.gov.hmcts.ccd.sdk.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@Getter
@RequiredArgsConstructor
@ComplexType(name = "YesOrNo", generate = false)
public enum YesOrNo {
  YES("Yes"),
  NO("No");

  private final String value;

  public static YesOrNo from(Boolean val) {
    return val ? YES : NO;
  }

  public boolean toBoolean(YesOrNo val) {
    return this.equals(YES);
  }
}

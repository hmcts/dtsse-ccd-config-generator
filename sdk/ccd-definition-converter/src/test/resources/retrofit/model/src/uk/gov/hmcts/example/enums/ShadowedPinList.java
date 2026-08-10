package uk.gov.hmcts.example.enums;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A team enum that carries BOTH a per-constant {@code @JsonProperty} and a {@code @JsonValue} accessor —
 * prl's {@code DocumentPartyEnum} shape, which pins {@code @JsonProperty("Court")} onto {@code COURT} and
 * still serialises through {@code getDisplayedValue()}.
 *
 * <p>The {@code @JsonValue} wins, so the code this constant really emits is its own name
 * ({@code COURT}), NOT the {@code @JsonProperty} value. The label decision has to know that: reading the
 * annotation blindly concludes the constant emits {@code Court}, sees the definition's {@code ListElement}
 * is also {@code Court}, and drops the label pin the list needs.
 */
public enum ShadowedPinList {

  @JsonProperty("Court")
  COURT("Court");

  private final String displayedValue;

  ShadowedPinList(String displayedValue) {
    this.displayedValue = displayedValue;
  }

  @JsonValue
  public String getDisplayedValue() {
    return displayedValue;
  }
}

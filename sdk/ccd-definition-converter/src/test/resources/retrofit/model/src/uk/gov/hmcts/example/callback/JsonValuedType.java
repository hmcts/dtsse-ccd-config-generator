package uk.gov.hmcts.example.callback;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The same shape as {@link ScannedDocumentType} — a fixed list modelled as an enum no field declares, and
 * whose constant NAMES do match the definition's ListElementCodes — but with a {@code @JsonValue} that
 * redirects what Jackson serialises the constant as. FixedListGenerator puts the constant itself into the
 * row map, so the emitted ListElementCode is this method's return value ({@code first}/{@code second}),
 * not the constant name (sscs's {@code DocumentTabChoice} shape). Naming it would emit wrong codes even
 * though a name comparison passes, so it is refused.
 */
public enum JsonValuedType {
  FIRST("first"),
  SECOND("second");

  private final String value;

  JsonValuedType(String value) {
    this.value = value;
  }

  @Override
  @JsonValue
  public String toString() {
    return value;
  }
}

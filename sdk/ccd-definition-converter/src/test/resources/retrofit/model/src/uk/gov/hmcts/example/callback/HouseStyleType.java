package uk.gov.hmcts.example.callback;

/**
 * The same shape as {@link ScannedDocumentType} — a fixed list modelled as an enum no field declares —
 * except that the team spells the definition's ListElementCodes in its own house style, carrying them as
 * a constructor field. FixedListGenerator emits whatever Jackson serialises the constant as, so each
 * constant is pinned to its definition code with a {@code @JsonProperty} and the enum can then supply
 * the list's rows.
 */
public enum HouseStyleType {
  FIRST_STYLE("firstStyle"),
  SECOND_STYLE("secondStyle");

  private final String value;

  HouseStyleType(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }
}

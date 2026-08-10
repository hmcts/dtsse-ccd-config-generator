package uk.gov.hmcts.example.callback;

/**
 * The same shape as {@link ScannedDocumentType} — a fixed list modelled as an enum no field declares —
 * except that the team spells the definition's ListElementCodes in its own house style, carrying them as
 * a constructor field. FixedListGenerator emits ListElementCode as the CONSTANT NAME and nothing can pin
 * any other value, so naming this enum would emit a list of wrong codes where today it emits none.
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

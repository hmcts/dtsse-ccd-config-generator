package uk.gov.hmcts.example.callback;

/**
 * A fixed list whose definition code has no constant NAMED after it, but whose value the enum already
 * models under another name: {@code SIXTY_MINUTES} passes the same label the definition's {@code HOUR_1}
 * row carries (civil's {@code HearingLengthFinalOrderList} declares nineteen constants for a six-code
 * list, {@code MINUTES_60("1 hour")} among them).
 *
 * <p>Adding a constant closes a GAP — a value the CCD column carries that the enum cannot name. Here the
 * value is not missing, so adding {@code HOUR_1("1 hour")} would put two ways to say one thing into the
 * team's enum and still leave the extra constant emitting an extra row. That is a code-spelling
 * divergence to report, not a constant to synthesise, so the whole enum is refused.
 */
public enum RestatedType {

  FIFTEEN_MINUTES("15 minutes"),

  SIXTY_MINUTES("1 hour");

  private final String label;

  RestatedType(String label) {
    this.label = label;
  }

  public String getLabel() {
    return label;
  }
}

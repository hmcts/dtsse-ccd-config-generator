package uk.gov.hmcts.example.model.common;

/**
 * A model class that reads {@code Party.readSummary} through its accessor and assigns the result to
 * {@code SharedSummary}. Re-declaring that field as a generated companion would change the getter's
 * return type and stop this compiling, so the retrofit retype must refuse it and report a gap.
 */
public class SummaryReader {

  public String read(Party party) {
    SharedSummary summary = party.getReadSummary();
    return summary == null ? null : "read";
  }

  /**
   * Sets {@code Party.builderSetSummary} through a Lombok builder method named after the field, which
   * carries no {@code get}/{@code set} prefix for the accessor check to match on. Re-declaring the
   * field changes that method's parameter type, so the call would stop compiling.
   */
  public Party build(SharedSummary summary) {
    return Party.builder().builderSetSummary(summary).build();
  }
}

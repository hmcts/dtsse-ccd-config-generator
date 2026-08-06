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
}

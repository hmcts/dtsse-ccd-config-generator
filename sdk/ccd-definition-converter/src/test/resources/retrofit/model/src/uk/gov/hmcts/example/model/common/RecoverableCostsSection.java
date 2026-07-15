package uk.gov.hmcts.example.model.common;

/**
 * A subclass that calls {@link RecoverableCosts}'s Lombok all-args constructor positionally via
 * {@code super(...)}. This is what makes synthesising a field into {@code RecoverableCosts} unsafe:
 * a widened all-args constructor breaks this fixed-arity super call.
 */
public class RecoverableCostsSection extends RecoverableCosts {

  private String bandText;

  public RecoverableCostsSection(String band, String reasons, String bandText) {
    super(band, reasons);
    this.bandText = bandText;
  }
}

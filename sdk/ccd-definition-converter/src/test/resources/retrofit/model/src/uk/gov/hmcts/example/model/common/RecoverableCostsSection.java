package uk.gov.hmcts.example.model.common;

/**
 * A subclass that calls {@link RecoverableCosts}'s Lombok all-args constructor positionally via
 * {@code super(...)}. This is what makes a naive field synthesis into {@code RecoverableCosts} unsafe:
 * a widened all-args constructor breaks this fixed-arity super call.
 *
 * <p>This file must stay UNPATCHED — the repair adds a narrow constructor to the parent that this
 * {@code super(band, reasons)} call binds to, so the subclass needs no edit. That is also what protects
 * subclasses outside the parsed source tree (a published model jar's consumers).
 */
public class RecoverableCostsSection extends RecoverableCosts {

  private String bandText;

  public RecoverableCostsSection(String band, String reasons, String bandText) {
    super(band, reasons);
    this.bandText = bandText;
  }
}

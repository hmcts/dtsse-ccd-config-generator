package uk.gov.hmcts.rt.model.common;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * The subclass whose fixed-arity {@code super(band, reasons)} call the retrofit patch must keep binding,
 * mirroring civil's {@code FixedRecoverableCostsSection}. This file receives NO patch hunks: the narrow
 * constructor added to {@link SuperCosts} is what this call resolves to once the Lombok-generated
 * all-args constructor grows a third parameter.
 *
 * <p>Also constructs the parent positionally and via the builder, so both bindings are compile-checked.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SuperCostsSection extends SuperCosts {

  private String bandText;

  public SuperCostsSection(String band, String reasons, String bandText) {
    super(band, reasons);
    this.bandText = bandText;
  }

  public static SuperCosts positionalParent() {
    return new SuperCosts("band", "reasons");
  }
}

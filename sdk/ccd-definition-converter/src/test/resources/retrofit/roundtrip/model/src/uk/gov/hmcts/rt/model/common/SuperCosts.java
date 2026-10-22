package uk.gov.hmcts.rt.model.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A complex type in civil's {@code FixedRecoverableCosts} shape: an EXPLICIT
 * {@code @AllArgsConstructor} whose generated constructor a subclass ({@link SuperCostsSection}) calls
 * positionally via {@code super(...)}. The definition declares a member ({@code costsLabel}) this class
 * has no field for, so the synthesised field widens that generated constructor from two args to three.
 *
 * <p>The patch keeps the subclass compiling by adding an explicit NARROW constructor here over the
 * pre-synthesis field list, delegating {@code this(band, reasons, null)}. {@link SuperCostsSection} is
 * left untouched, so the round-trip's compile step is what proves the repair works — and it is the same
 * mechanism that protects subclasses outside the parsed source tree (a published model jar's consumers).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SuperCosts {

  private String band;

  private String reasons;
}
